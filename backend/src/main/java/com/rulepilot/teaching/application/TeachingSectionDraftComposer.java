package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces one source-cited lesson section from selected evidence.
 *
 * <p>The caller owns section ordering, retrieval, and publication. This boundary owns only untrusted model output:
 * request assembly, visual/text recovery, normalization, evidence validation, and the matching audit activities.</p>
 */
final class TeachingSectionDraftComposer {

    private static final Logger log = LoggerFactory.getLogger(TeachingSectionDraftComposer.class);

    private final TeachingLessonModel model;
    private final EvidenceVerifier evidenceVerifier;
    private final AuditedAgentInvocations invocations;
    private final VisualRulebookPageFacts visualFacts;
    private final LessonDraftPresentationNormalizer presentationNormalizer = new LessonDraftPresentationNormalizer();
    private final TeachingDraftRecoveryPolicy draftRecoveryPolicy = new TeachingDraftRecoveryPolicy();

    TeachingSectionDraftComposer(
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts) {
        this.model = model;
        this.evidenceVerifier = evidenceVerifier;
        this.invocations = invocations;
        this.visualFacts = visualFacts;
    }

    TeachingSectionDraftCandidate compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            int sectionIndex,
            boolean includeVisualEvidence) {
        boolean requiresVisualGrounding = includeVisualEvidence
                || TeachingVisualEvidenceSelector.hasVisualPageEvidence(evidence);
        List<TeachingLessonModel.PageImageInput> pageImages = requiresVisualGrounding
                ? TeachingVisualEvidenceSelector.select(
                        planned, evidence, model.supportsVisualEvidence(plan.createdBy()))
                : List.of();
        if (!pageImages.isEmpty()) {
            log.info(
                    "Teaching topic {} selected visual evidence pages {}",
                    planned.topicKey(),
                    pageImages.stream().map(TeachingLessonModel.PageImageInput::pageNumber).toList());
        }
        TeachingLessonModel.SectionRequest modelRequest = new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                planned.coverageTags(),
                plan.playerCount(),
                plan.beginnerCount(),
                plan.durationMinutes(),
                pacing.durationSeconds(),
                pacing.maxSteps(),
                priorSections,
                modelEvidence(plan.documentVersionId(), evidence),
                pageImages,
                planned.retrievalQueries(),
                plan.createdBy(),
                chapterScope(plan, planned));
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
        draft = normalizeDraft(draft, modelRequest);
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
                    throw rejectedDraft;
                }
                String diagnostic = rejectedDraft.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : rejectedDraft.getMessage();
                List<String> feedback = draftRecoveryPolicy.repairFeedback(
                        diagnostic, hasPageImages, isVisualLocalizationFailure(rejectedDraft));
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
                draft = normalizeDraft(draft, modelRequest);
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
        textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest);
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
                textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest);
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
        return presentationNormalizer.normalize(draft, request);
    }

    LessonSection validatedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft,
            EvidenceStatus evidenceStatus) {
        LessonDraftValidator.validateDraft(draft, modelRequest);

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        LessonDraftValidator.validateVisualBlockEvidence(draft, modelRequest, allowedEvidence);
        List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence);
        List<Claim> reviewClaims = LessonDraftValidator.reviewClaims(draft, visualCitationIds);
        List<EvidenceClaim> generatedClaims = reviewClaims.stream()
                .map(claim -> new EvidenceClaim(claim.text(), claim.citationIds()))
                .toList();
        var verification = evidenceVerifier.verify(new VerificationRequest(
                plan.documentVersionId(),
                evidence.stream().map(this::toVerifierEvidence).toList(),
                generatedClaims));
        if (!verification.verified()) {
            throw new IllegalArgumentException(
                    "Evidence validation failed: " + String.join(", ", verification.issueCodes()));
        }
        List<LessonStep> steps = IntStream.range(0, draft.steps().size())
                .mapToObj(index -> LessonDraftValidator.validatedStep(
                        index + 1, draft.steps().get(index), allowedEvidence))
                .toList();
        List<Integer> visualSourcePages = visualCitationIds.stream()
                .map(allowedEvidence::get)
                .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .sorted()
                .boxed()
                .toList();
        return new LessonSection(
                planned.position(),
                planned.topicKey(),
                planned.coverageTags(),
                draft.title().strip(),
                planned.required(),
                evidenceStatus,
                draft.visualKind(),
                draft.visualCaption().strip(),
                visualSourcePages,
                visualCitationIds,
                steps);
    }

    private List<EvidenceInput> modelEvidence(UUID documentVersionId, List<RuleEvidence> evidence) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toSet());
        Map<Integer, String> factsByPage = visualFacts.find(documentVersionId, pages).stream()
                .collect(Collectors.toMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::evidenceText));
        return evidence.stream().map(source -> toModelEvidence(source, factsByPage)).toList();
    }

    private EvidenceInput toModelEvidence(RuleEvidence evidence, Map<Integer, String> factsByPage) {
        String visualFact = evidence.pageFrom() == evidence.pageTo() ? factsByPage.get(evidence.pageFrom()) : null;
        String excerpt = visualFact == null ? evidence.excerpt() : evidence.excerpt() + "\n\n" + visualFact;
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                excerpt,
                evidence.pageFrom(),
                evidence.pageTo());
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
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

    private static String chapterScope(TeachingPlan plan, TeachingPlan.PlannedSection current) {
        String chapters = plan.sections().stream()
                .map(section -> (section.position() == current.position() ? "【当前章节】" : "")
                        + "第" + section.position() + "章《" + section.title() + "》："
                        + boundedChapterObjective(section.objective()))
                .collect(Collectors.joining("\n"));
        String scope = "完整章节分工（仅界定讲解边界，不是规则事实）：\n" + chapters
                + "\n当前章节只完整讲解自己的目标。其他章节已经明确负责的机制，只保留本章理解所必需的"
                + "阶段名、顺序、即时选择或结果；不要复述它们的触发、数量、成本、例外、计算、完整流程或图例映射。";
        return scope.length() <= 4_000 ? scope : scope.substring(0, 3_999) + "…";
    }

    private static String boundedChapterObjective(String objective) {
        String value = objective == null ? "" : objective.strip();
        return value.length() <= 280 ? value : value.substring(0, 279) + "…";
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
