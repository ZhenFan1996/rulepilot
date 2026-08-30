package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.CandidateRejection;
import com.rulepilot.teaching.TeachingLessonModel.InputTokenProfile;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.ModelInvocation;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single section-Agent conversation and its deterministic publication boundary.
 *
 * <p>A valid first candidate is published after one model call. A rejected candidate becomes one complete observation
 * for the same Agent, which must return a complete replacement. The persisted run deadline, cancellation, and global
 * resource budget stop useful progress; this class has no separate call-count or retry choreography.</p>
 */
final class TeachingSectionDraftComposer {

    private static final Logger log = LoggerFactory.getLogger(TeachingSectionDraftComposer.class);

    private final TeachingLessonModel model;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionModelRequestFactory requestFactory;
    private final TeachingSectionCandidateValidator candidateValidator;

    TeachingSectionDraftComposer(
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts) {
        this.model = model;
        this.invocations = invocations;
        this.requestFactory = new TeachingSectionModelRequestFactory(visualFacts);
        this.candidateValidator = new TeachingSectionCandidateValidator(evidenceVerifier);
    }

    TeachingSectionDraftCandidate compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            int sectionIndex) {
        TeachingLessonModel.SectionRequest request = requestFactory.create(
                plan,
                planned,
                priorSections,
                evidence);
        return composeUntilAccepted(
                plan, planned, evidence, request, assistantRunId, sectionIndex, "CITED_DRAFT_ACCEPTED");
    }

    private TeachingSectionDraftCandidate composeUntilAccepted(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest request,
            UUID runId,
            int sectionIndex,
            String acceptedCategory) {
        CandidateRejection latestRejection = null;
        Set<CandidateRejection> seenRejections = new LinkedHashSet<>();
        int observationIndex = 0;
        while (true) {
            SectionDraft candidate;
            try {
                candidate = invokeAgentTurn(runId, planned, request, latestRejection, observationIndex);
            } catch (InvalidOutputException invalidOutput) {
                CandidateRejection rejection = model.rejectionObservation(
                        request,
                        invalidOutput.rejectedCandidate(),
                        invalidOutput.code(),
                        invalidOutput.path(),
                        invalidOutput.reason());
                latestRejection = acceptProgressOrFail(
                        runId,
                        planned,
                        observationIndex++,
                        seenRejections,
                        rejection,
                        "MODEL_OUTPUT_CONTRACT_REJECTED");
                continue;
            }
            try {
                LessonSection accepted = candidateValidator.validate(
                        plan, planned, evidence, request, candidate, EvidenceStatus.CITED_DRAFT);
                recordValidation(
                        runId,
                        planned,
                        observationIndex,
                        ActivityOutcome.SUCCEEDED,
                        acceptedCategory);
                return new TeachingSectionDraftCandidate(
                        sectionIndex, planned, evidence, request, candidate, accepted);
            } catch (IllegalArgumentException validationFailure) {
                String exactError = validationFailure.getMessage() == null
                        ? validationFailure.getClass().getName()
                        : validationFailure.getMessage();
                CandidateRejection rejection =
                        model.rejectionObservation(
                                request,
                                candidate,
                                "PUBLICATION_BOUNDARY_REJECTED",
                                "$",
                                exactError);
                latestRejection = acceptProgressOrFail(
                        runId,
                        planned,
                        observationIndex++,
                        seenRejections,
                        rejection,
                        "DRAFT_VALIDATION_REJECTED");
            }
        }
    }

    private CandidateRejection acceptProgressOrFail(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            int observationIndex,
            Set<CandidateRejection> seenRejections,
            CandidateRejection current,
            String rejectedCategory) {
        if (!seenRejections.add(current)) {
            invocations.record(
                    runId,
                    ActivityType.VALIDATION,
                    "settleTeachingSectionNoProgress|" + planned.position() + "|" + observationIndex,
                    ActivityOutcome.REJECTED,
                    "Teaching section stopped: a previously rejected complete candidate and observation repeated");
            throw new IllegalArgumentException(
                    "teaching section Agent made no progress: it repeated a previously rejected complete candidate, exact validation error, output contract, and allowed identities");
        }
        recordValidation(
                runId,
                planned,
                observationIndex,
                ActivityOutcome.REJECTED,
                rejectedCategory);
        log.info(
                "Teaching topic {} returned a rejected candidate; continuing the same Agent with the complete validation observation",
                planned.topicKey());
        return current;
    }

    private SectionDraft invokeAgentTurn(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            CandidateRejection rejection,
            int observationIndex) {
        boolean continuation = rejection != null;
        InputTokenProfile profile = continuation
                ? model.continuationInputProfile(request, rejection)
                : model.compositionInputProfile(request);
        String summary = continuation
                ? "Teaching section replacement candidate received after validation feedback"
                : "Teaching section candidate received";
        ModelInvocation invocation = invocations.invoke(
                runId,
                ActivityType.MODEL,
                operationName(
                        continuation ? "continueTeachingSectionAfterRejection" : "composeTeachingSection",
                        planned.position(),
                        observationIndex),
                profile.totalTokens(),
                profiledSummary(summary, profile),
                () -> continuation
                        ? model.continueAfterRejectionInvocation(request, rejection)
                        : model.composeInvocation(request),
                result -> outputTokens(request, result),
                result -> profiledSummary(summary, profile, result));
        return invocation.draft();
    }

    private void recordValidation(
            UUID runId,
            TeachingPlan.PlannedSection section,
            int observationIndex,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "validateTeachingSection|" + section.position() + "|" + observationIndex,
                outcome,
                "Teaching draft " + (outcome == ActivityOutcome.SUCCEEDED ? "accepted: " : "rejected: ") + category);
    }

    private String profiledSummary(String summary, InputTokenProfile profile) {
        return "%s [p=%s;contract=%d;evidence=%d;continuity=%d;revision=%d]"
                .formatted(
                        summary,
                        profile.providerId(),
                        profile.contractTokens(),
                        profile.evidenceTokens(),
                        profile.continuityTokens(),
                        profile.revisionTokens());
    }

    private int outputTokens(TeachingLessonModel.SectionRequest request, ModelInvocation invocation) {
        return invocation.completionTokens() > 0
                ? invocation.completionTokens()
                : model.estimatedOutputTokens(request, invocation.draft());
    }

    private String profiledSummary(String summary, InputTokenProfile profile, ModelInvocation invocation) {
        return profiledSummary(summary, profile) + " u=i:%d,o:%d,h:%d".formatted(
                invocation.promptTokens(),
                invocation.completionTokens(),
                invocation.cacheReadInputTokens());
    }

    private String operationName(String operation, int sectionPosition, int observationIndex) {
        return operation + "|" + sectionPosition + "|" + observationIndex;
    }
}
