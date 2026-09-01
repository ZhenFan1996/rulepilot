package com.rulepilot.teaching.application;

import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.InputTokenProfile;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.ModelInvocation;
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
        return compose(
                plan,
                planned,
                priorSections,
                evidence,
                assistantRunId,
                sectionIndex,
                includeVisualEvidence,
                true,
                CaptureHandle.noop());
    }

    TeachingSectionDraftCandidate compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            int sectionIndex,
            boolean includeVisualEvidence,
            boolean allowValidationRevision) {
        return compose(
                plan,
                planned,
                priorSections,
                evidence,
                assistantRunId,
                sectionIndex,
                includeVisualEvidence,
                allowValidationRevision,
                CaptureHandle.noop());
    }

    TeachingSectionDraftCandidate compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            int sectionIndex,
            boolean includeVisualEvidence,
            boolean allowValidationRevision,
            CaptureHandle capture) {
        capture = PrivateAgentTraceCapture.failOpen(capture);
        TeachingLessonModel.SectionRequest modelRequest = requestFactory.create(
                plan,
                planned,
                priorSections,
                evidence,
                includeVisualEvidence,
                model.supportsVisualEvidence(plan.createdBy()));
        if (!modelRequest.pageImages().isEmpty()) {
            log.info(
                    "Teaching section {} selected {} visual evidence page(s)",
                    planned.position(),
                    modelRequest.pageImages().size());
        }
        SectionDraft draft;
        try {
            draft = composeModelDraft(assistantRunId, planned, modelRequest, capture);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException visualCompositionFailure) {
            if (canFallbackToCitedText(modelRequest, evidence)) {
                log.warn(
                        "Visual teaching composition for section {} is unavailable; continuing with cited text (failureType={})",
                        planned.position(),
                        visualCompositionFailure.getClass().getSimpleName());
                recordVisualTextFallback(assistantRunId, planned);
                return fallbackToTextDraft(
                        plan, planned, evidence, modelRequest, assistantRunId, sectionIndex, 0, capture);
            }
            throw visualCompositionFailure;
        }
        draft = normalizeDraft(draft, modelRequest, evidence);
        boolean hasPageImages = !modelRequest.pageImages().isEmpty();
        int maxRepairAttempts = allowValidationRevision
                ? draftRecoveryPolicy.maxRepairAttempts(hasPageImages)
                : 0;
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
                        "DRAFT_VALIDATION_REJECTED");
                if (repair == maxRepairAttempts) {
                    throw rejectedDraft;
                }
                String diagnostic = rejectedDraft.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : candidateValidator.repairDiagnostic(rejectedDraft, draft, evidence);
                List<String> feedback = draftRecoveryPolicy.repairFeedback(
                        diagnostic, hasPageImages, false);
                log.info(
                        "Teaching section {} structural repair {}/{} requested",
                        planned.position(),
                        repair + 1,
                        maxRepairAttempts);
                SectionDraft draftToRevise = draft;
                try {
                    draft = reviseModelDraft(
                            assistantRunId,
                            planned,
                            modelRequest,
                            draftToRevise,
                            feedback,
                            "reviseTeachingSection",
                            "repairTeachingSectionRevisionContract",
                            "Teaching section revised from validation feedback",
                            capture);
                } catch (AgentExecutionStoppedException stopped) {
                    throw stopped;
                } catch (RuntimeException visualRepairFailure) {
                    if (canFallbackToCitedText(modelRequest, evidence)) {
                        log.warn(
                                "Visual teaching repair for section {} is unavailable; continuing with cited text (failureType={})",
                                planned.position(),
                                visualRepairFailure.getClass().getSimpleName());
                        recordVisualTextFallback(assistantRunId, planned);
                        return fallbackToTextDraft(
                                plan,
                                planned,
                                evidence,
                                modelRequest,
                                assistantRunId,
                                sectionIndex,
                                repair + 1,
                                capture);
                    }
                    throw visualRepairFailure;
                }
                draft = candidateValidator.mergeRepairPreservingValidatedFields(
                        plan,
                        planned,
                        evidence,
                        modelRequest,
                        draftToRevise,
                        normalizeDraft(draft, modelRequest, evidence));
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
            int validationAttempt,
            CaptureHandle capture) {
        TeachingLessonModel.SectionRequest textOnlyRequest = withoutPageImages(visualRequest);
        SectionDraft textOnlyDraft = composeModelDraft(
                assistantRunId,
                planned,
                textOnlyRequest,
                "fallbackToTextTeachingSection",
                "repairTextTeachingSectionContract",
                "Visual teaching section recomposed as complete grounded text",
                capture);
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
                        "TEXT_FALLBACK_REJECTED");
                if (repair == draftRecoveryPolicy.maxRepairAttempts(false)) {
                    throw rejectedFallback;
                }
                String diagnostic = rejectedFallback.getMessage() == null
                        ? "The previous text fallback failed lesson validation."
                        : candidateValidator.repairDiagnostic(rejectedFallback, textOnlyDraft, evidence);
                SectionDraft previousDraft = textOnlyDraft;
                textOnlyDraft = reviseModelDraft(
                        assistantRunId,
                        planned,
                        textOnlyRequest,
                        previousDraft,
                        List.of(diagnostic),
                        "reviseTextTeachingSection",
                        "repairTextTeachingSectionRevisionContract",
                        "Text fallback revised from validation feedback",
                        capture);
                textOnlyDraft = candidateValidator.mergeRepairPreservingValidatedFields(
                        plan,
                        planned,
                        evidence,
                        textOnlyRequest,
                        previousDraft,
                        normalizeDraft(textOnlyDraft, textOnlyRequest, evidence));
            }
        }
    }

    private boolean canFallbackToCitedText(
            TeachingLessonModel.SectionRequest request, List<RuleEvidence> evidence) {
        return !request.pageImages().isEmpty()
                && evidence.stream().anyMatch(source -> source.contentKind() == RuleEvidence.ContentKind.CANONICAL_TEXT
                        || source.contentKind() == RuleEvidence.ContentKind.CANONICAL_TEXT_WITH_VISUAL_FACTS);
    }

    private TeachingLessonModel.SectionRequest withoutPageImages(TeachingLessonModel.SectionRequest request) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.priorSections(),
                request.evidence(),
                List.of(),
                request.requiredRuleIntents(),
                request.teachingUnits(),
                request.modelConfigurationOwner(),
                request.chapterScope(),
                request.wholeGameContext());
    }

    private SectionDraft composeModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request) {
        return composeModelDraft(runId, planned, request, CaptureHandle.noop());
    }

    private SectionDraft composeModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            CaptureHandle capture) {
        return composeModelDraft(
                runId,
                planned,
                request,
                "composeTeachingSection",
                "repairTeachingSectionContract",
                "Teaching section model output received",
                capture);
    }

    private SectionDraft composeModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            String primaryOperation,
            String repairOperation,
            String successSummary) {
        return composeModelDraft(
                runId,
                planned,
                request,
                primaryOperation,
                repairOperation,
                successSummary,
                CaptureHandle.noop());
    }

    private SectionDraft composeModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            String primaryOperation,
            String repairOperation,
            String successSummary,
            CaptureHandle capture) {
        InputTokenProfile primaryProfile = model.compositionInputProfile(request);
        TraceEventContext traceContext = modelContext(runId);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    operationName(primaryOperation, planned.position()),
                    primaryProfile.totalTokens(),
                    profiledSummary(successSummary, primaryProfile),
                    () -> capture.enabled()
                            ? model.composeInvocation(request, capture, traceContext, 1)
                            : model.composeInvocation(request),
                    result -> outputTokens(request, result),
                    result -> profiledSummary(successSummary, primaryProfile, result))
                    .draft();
        } catch (InvalidOutputException firstFailure) {
            InputTokenProfile repairProfile = model.compositionRepairInputProfile(request);
            try {
                return invocations.invoke(
                        runId,
                        ActivityType.MODEL,
                        operationName(repairOperation, planned.position()),
                        repairProfile.totalTokens(),
                        profiledSummary("Teaching section structured output repaired", repairProfile),
                        () -> capture.enabled()
                                ? model.repairCompositionContractInvocation(request, capture, traceContext, 2)
                                : model.repairCompositionContractInvocation(request),
                        result -> outputTokens(request, result),
                        result -> profiledSummary(
                                "Teaching section structured output repaired", repairProfile, result))
                        .draft();
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
        }
    }

    SectionDraft reviseModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            SectionDraft previousDraft,
            List<String> feedback,
            String primaryOperation,
            String repairOperation,
            String successSummary) {
        return reviseModelDraft(
                runId,
                planned,
                request,
                previousDraft,
                feedback,
                primaryOperation,
                repairOperation,
                successSummary,
                CaptureHandle.noop());
    }

    SectionDraft reviseModelDraft(
            UUID runId,
            TeachingPlan.PlannedSection planned,
            TeachingLessonModel.SectionRequest request,
            SectionDraft previousDraft,
            List<String> feedback,
            String primaryOperation,
            String repairOperation,
            String successSummary,
            CaptureHandle capture) {
        InputTokenProfile primaryProfile = model.revisionInputProfile(request, previousDraft, feedback);
        TraceEventContext traceContext = modelContext(runId);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    operationName(primaryOperation, planned.position()),
                    primaryProfile.totalTokens(),
                    profiledSummary(successSummary, primaryProfile),
                    () -> capture.enabled()
                            ? model.reviseInvocation(
                                    request, previousDraft, feedback, capture, traceContext, 1)
                            : model.reviseInvocation(request, previousDraft, feedback),
                    result -> outputTokens(request, result),
                    result -> profiledSummary(successSummary, primaryProfile, result))
                    .draft();
        } catch (InvalidOutputException firstFailure) {
            InputTokenProfile repairProfile = model.revisionRepairInputProfile(request, previousDraft, feedback);
            try {
                return invocations.invoke(
                        runId,
                        ActivityType.MODEL,
                        operationName(repairOperation, planned.position()),
                        repairProfile.totalTokens(),
                        profiledSummary("Teaching section revision structured output repaired", repairProfile),
                        () -> capture.enabled()
                                ? model.repairRevisionContractInvocation(
                                        request, previousDraft, feedback, capture, traceContext, 2)
                                : model.repairRevisionContractInvocation(request, previousDraft, feedback),
                        result -> outputTokens(request, result),
                        result -> profiledSummary(
                                "Teaching section revision structured output repaired", repairProfile, result))
                        .draft();
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
        }
    }

    SectionDraft normalizeDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        return normalizeDraft(draft, request, List.of());
    }

    SectionDraft normalizeDraft(
            SectionDraft draft, TeachingLessonModel.SectionRequest request, List<RuleEvidence> evidence) {
        return presentationNormalizer.normalize(draft, request);
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

    private String profiledSummary(String summary, InputTokenProfile profile) {
        return "%s [p=%s;f=%d;o=%d;r=%d;e=%d;s=%d;c=%d;v=%d;x=%d]"
                .formatted(
                        summary,
                        profile.providerId(),
                        profile.fixedContractTokens(),
                        profile.objectiveTokens(),
                        profile.requiredRuleTokens(),
                        profile.evidenceTokens(),
                        profile.chapterScopeTokens(),
                        profile.continuityTokens(),
                        profile.revisionTokens(),
                        profile.otherRequestTokens());
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

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }

    private TraceEventContext modelContext(UUID runId) {
        return TraceEventContext.create(
                java.time.Instant.now(),
                JourneyStage.TEACHING,
                UUID.randomUUID(),
                runId,
                new ResourceRef(ResourceType.TEACHING_RUN, runId));
    }
}
