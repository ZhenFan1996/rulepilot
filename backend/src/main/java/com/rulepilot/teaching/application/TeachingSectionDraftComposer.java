package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces one source-cited lesson section from selected evidence.
 *
 * <p>The caller owns section ordering, retrieval, and publication. This boundary owns only untrusted model output:
 * model calls, visual/text recovery, normalization, evidence validation, and the matching audit activities.</p>
 */
final class TeachingSectionDraftComposer {

    private static final Logger log = LoggerFactory.getLogger(TeachingSectionDraftComposer.class);

    private final TeachingLessonModel model;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionModelRequestFactory requestFactory;
    private final TeachingSectionCandidateValidator candidateValidator;
    private final LessonDraftPresentationNormalizer presentationNormalizer = new LessonDraftPresentationNormalizer();
    private final TeachingDraftRecoveryPolicy draftRecoveryPolicy = new TeachingDraftRecoveryPolicy();

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
            int sectionIndex,
            boolean includeVisualEvidence) {
        TeachingLessonModel.SectionRequest modelRequest = requestFactory.create(
                plan,
                planned,
                priorSections,
                evidence,
                includeVisualEvidence,
                model.supportsVisualEvidence(plan.createdBy()));
        if (!modelRequest.pageImages().isEmpty()) {
            log.info(
                    "Teaching topic {} selected visual evidence pages {}",
                    planned.topicKey(),
                    modelRequest.pageImages().stream()
                            .map(TeachingLessonModel.PageImageInput::pageNumber)
                            .toList());
        }
        SectionDraft draft;
        try {
            draft = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    operationName("composeTeachingSection", planned.position()),
                    estimateTokens(modelRequest.toString()),
                    "Teaching section model output received",
                    () -> model.compose(modelRequest),
                    result -> estimateTokens(result.toString()));
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException visualCompositionFailure) {
            if (draftRecoveryPolicy.canFallbackToCitedText(
                    !modelRequest.pageImages().isEmpty(), hasOnlyVisualPageEvidence(evidence))) {
                log.warn(
                        "Visual teaching composition for topic {} is unavailable; continuing with cited text: {}",
                        planned.topicKey(),
                        visualCompositionFailure.getMessage());
                recordVisualTextFallback(assistantRunId, planned);
                return fallbackToTextDraft(
                        plan, planned, evidence, modelRequest, assistantRunId, sectionIndex, 0);
            }
            throw visualCompositionFailure;
        }
        draft = normalizeDraft(draft, modelRequest, evidence);
        boolean hasPageImages = !modelRequest.pageImages().isEmpty();
        boolean hasOnlyVisualPageEvidence = hasOnlyVisualPageEvidence(evidence);
        int maxRepairAttempts = draftRecoveryPolicy.maxRepairAttempts(hasPageImages);
        for (int repair = 0; ; repair++) {
            try {
                LessonSection accepted = validatedSection(
                        plan, planned, evidence, modelRequest, draft, EvidenceStatus.CITED_DRAFT);
                recordValidation(
                        assistantRunId,
                        planned,
                        repair,
                        ActivityOutcome.SUCCEEDED,
                        "CITED_DRAFT_ACCEPTED");
                return new TeachingSectionDraftCandidate(
                        sectionIndex, planned, evidence, modelRequest, draft, accepted);
            } catch (IllegalArgumentException rejectedDraft) {
                recordValidation(
                        assistantRunId,
                        planned,
                        repair,
                        ActivityOutcome.REJECTED,
                        TeachingDraftRejectionCategory.from(rejectedDraft));
                IllegalArgumentException effectiveRejection = rejectedDraft;
                SectionDraft timingPreserved = draftRecoveryPolicy.preserveCitedEndOfRoundTiming(draft, evidence);
                if (timingPreserved != draft) {
                    try {
                        LessonSection accepted = validatedSection(
                                plan, planned, evidence, modelRequest, timingPreserved, EvidenceStatus.CITED_DRAFT);
                        recordValidation(
                                assistantRunId,
                                planned,
                                repair,
                                ActivityOutcome.SUCCEEDED,
                                "END_OF_ROUND_TIMING_GUARDRAIL_APPLIED");
                        return new TeachingSectionDraftCandidate(
                                sectionIndex, planned, evidence, modelRequest, timingPreserved, accepted);
                    } catch (IllegalArgumentException correctedDraftStillInvalid) {
                        draft = timingPreserved;
                        effectiveRejection = correctedDraftStillInvalid;
                    }
                }
                SectionDraft playerCountSchedulePreserved =
                        draftRecoveryPolicy.preserveCitedPlayerCountRoundSchedule(draft, evidence);
                if (playerCountSchedulePreserved != draft) {
                    try {
                        LessonSection accepted = validatedSection(
                                plan,
                                planned,
                                evidence,
                                modelRequest,
                                playerCountSchedulePreserved,
                                EvidenceStatus.CITED_DRAFT);
                        recordValidation(
                                assistantRunId,
                                planned,
                                repair,
                                ActivityOutcome.SUCCEEDED,
                                "PLAYER_COUNT_ROUND_SCHEDULE_GUARDRAIL_APPLIED");
                        return new TeachingSectionDraftCandidate(
                                sectionIndex, planned, evidence, modelRequest, playerCountSchedulePreserved, accepted);
                    } catch (IllegalArgumentException correctedDraftStillInvalid) {
                        draft = playerCountSchedulePreserved;
                        effectiveRejection = correctedDraftStillInvalid;
                    }
                }
                if (draftRecoveryPolicy.shouldFallbackToCitedText(
                        hasPageImages, hasOnlyVisualPageEvidence, repair)) {
                    return fallbackToTextDraft(
                            plan,
                            planned,
                            evidence,
                            modelRequest,
                            assistantRunId,
                            sectionIndex,
                            repair + 1);
                }
                if (repair == maxRepairAttempts) {
                    throw effectiveRejection;
                }
                String diagnostic = effectiveRejection.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : effectiveRejection.getMessage();
                List<String> feedback = draftRecoveryPolicy.repairFeedback(
                        diagnostic, hasPageImages, isVisualLocalizationFailure(effectiveRejection));
                log.info(
                        "Teaching topic {} structural repair {}/{}: {}",
                        planned.topicKey(),
                        repair + 1,
                        maxRepairAttempts,
                        feedback.getFirst());
                SectionDraft draftToRevise = draft;
                try {
                    draft = invocations.invoke(
                            assistantRunId,
                            ActivityType.MODEL,
                            operationName("reviseTeachingSection", planned.position()),
                            estimateTokens(modelRequest.toString()) + estimateTokens(draftToRevise.toString())
                                    + estimateTokens(feedback.toString()),
                            "Teaching section revised from validation feedback",
                            () -> model.revise(modelRequest, draftToRevise, feedback),
                            result -> estimateTokens(result.toString()));
                } catch (AgentExecutionStoppedException stopped) {
                    throw stopped;
                } catch (RuntimeException visualRepairFailure) {
                    if (draftRecoveryPolicy.canFallbackToCitedText(hasPageImages, hasOnlyVisualPageEvidence)) {
                        log.warn(
                                "Visual teaching repair for topic {} is unavailable; continuing with cited text: {}",
                                planned.topicKey(),
                                visualRepairFailure.getMessage());
                        recordVisualTextFallback(assistantRunId, planned);
                        return fallbackToTextDraft(
                                plan, planned, evidence, modelRequest, assistantRunId, sectionIndex, repair + 1);
                    }
                    throw visualRepairFailure;
                }
                draft = normalizeDraft(draft, modelRequest, evidence);
            }
        }
    }

    private TeachingSectionDraftCandidate fallbackToTextDraft(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest visualRequest,
            UUID assistantRunId,
            int sectionIndex,
            int validationAttempt) {
        TeachingLessonModel.SectionRequest textOnlyRequest = draftRecoveryPolicy.withoutPageImages(visualRequest);
        SectionDraft textOnlyDraft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("fallbackToTextTeachingSection", planned.position()),
                estimateTokens(textOnlyRequest.toString()),
                "Visual teaching section recomposed as complete grounded text",
                () -> model.compose(textOnlyRequest),
                result -> estimateTokens(result.toString()));
        textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest, evidence);
        for (int repair = 0; ; repair++) {
            try {
                LessonSection accepted = validatedSection(
                        plan, planned, evidence, textOnlyRequest, textOnlyDraft, EvidenceStatus.CITED_DRAFT);
                recordValidation(
                        assistantRunId,
                        planned,
                        validationAttempt + repair,
                        ActivityOutcome.SUCCEEDED,
                        "TEXT_FALLBACK_ACCEPTED");
                return new TeachingSectionDraftCandidate(
                        sectionIndex, planned, evidence, textOnlyRequest, textOnlyDraft, accepted);
            } catch (IllegalArgumentException rejectedFallback) {
                recordValidation(
                        assistantRunId,
                        planned,
                        validationAttempt + repair,
                        ActivityOutcome.REJECTED,
                        "TEXT_FALLBACK_" + TeachingDraftRejectionCategory.from(rejectedFallback));
                if (repair == draftRecoveryPolicy.maxRepairAttempts(false)) throw rejectedFallback;
                List<String> repairFeedback = draftRecoveryPolicy.textFallbackFeedback(
                        rejectedFallback.getMessage() == null
                                ? "The previous fallback failed lesson validation."
                                : rejectedFallback.getMessage());
                SectionDraft draftToRevise = textOnlyDraft;
                textOnlyDraft = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        operationName("reviseTextTeachingSection", planned.position()),
                        estimateTokens(textOnlyRequest.toString()) + estimateTokens(draftToRevise.toString())
                                + estimateTokens(repairFeedback.toString()),
                        "Text fallback revised from validation feedback",
                        () -> model.revise(textOnlyRequest, draftToRevise, repairFeedback),
                        result -> estimateTokens(result.toString()));
                textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest, evidence);
                textOnlyDraft = draftRecoveryPolicy.preserveTextOnlyPresentationMetadata(draftToRevise, textOnlyDraft);
            }
        }
    }

    private boolean isVisualLocalizationFailure(IllegalArgumentException rejection) {
        return TeachingDraftRejectionCategory.from(rejection).startsWith("VISUAL_");
    }

    private boolean hasOnlyVisualPageEvidence(List<RuleEvidence> evidence) {
        return !evidence.isEmpty()
                && evidence.stream().allMatch(TeachingVisualEvidenceSelector::isVisualPageEvidence);
    }

    SectionDraft normalizeDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        return normalizeDraft(draft, request, List.of());
    }

    SectionDraft normalizeDraft(
            SectionDraft draft, TeachingLessonModel.SectionRequest request, List<RuleEvidence> evidence) {
        SectionDraft normalized = presentationNormalizer.normalize(draft, request);
        normalized = draftRecoveryPolicy.removeUnsupportedTurnHandoff(normalized, evidence);
        return draftRecoveryPolicy.removeUnsupportedTerminalZoneClaim(normalized, evidence);
    }

    LessonSection validatedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft,
            EvidenceStatus evidenceStatus) {
        return candidateValidator.validate(plan, planned, evidence, modelRequest, draft, evidenceStatus);
    }

    void recordValidation(
            UUID runId,
            TeachingPlan.PlannedSection section,
            int revision,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "validateTeachingSection|" + section.position() + "|" + revision,
                outcome,
                "Teaching draft " + (outcome == ActivityOutcome.SUCCEEDED ? "accepted: " : "rejected: ") + category);
    }

    private void recordVisualTextFallback(UUID runId, TeachingPlan.PlannedSection section) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "fallbackVisualTeachingSection|" + section.position(),
                ActivityOutcome.SUCCEEDED,
                "Visual composition unavailable; continuing with cited text");
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
