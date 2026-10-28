package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Converts one normalized, untrusted model draft into a fully cited lesson section.
 *
 * <p>This is the only point where draft structure, visual scope, and every player-facing claim are accepted
 * together. It performs no retrieval, model call, or publication.</p>
 */
final class TeachingSectionCandidateValidator {

    private final EvidenceVerifier evidenceVerifier;

    TeachingSectionCandidateValidator(EvidenceVerifier evidenceVerifier) {
        this.evidenceVerifier = evidenceVerifier;
    }

    LessonSection validate(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft,
            EvidenceStatus evidenceStatus) {
        java.util.Set<UUID> requestEvidenceIds = modelRequest.evidence().stream()
                .map(TeachingLessonModel.EvidenceInput::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .filter(source -> requestEvidenceIds.contains(source.chunkId()))
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        LessonDraftValidator.validateDraft(draft);
        List<Claim> reviewClaims = LessonDraftValidator.reviewClaims(draft);
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
                .mapToObj(index -> withSelectedVisuals(LessonDraftValidator.validatedStep(
                        index + 1, draft.steps().get(index), allowedEvidence), draft.steps().get(index), modelRequest))
                .toList();
        List<UUID> citedIds = steps.stream()
                .flatMap(step -> step.sourceChunkIds().stream())
                .distinct()
                .toList();
        List<Integer> citedPages = steps.stream()
                .flatMap(step -> step.sourcePages().stream())
                .distinct()
                .sorted()
                .toList();
        return new LessonSection(
                planned.position(),
                planned.topicKey(),
                planned.coverageTags(),
                draft.title(),
                planned.required(),
                evidenceStatus,
                VisualKind.REFERENCE_CARD,
                draft.title(),
                citedPages,
                citedIds,
                steps);
    }

    private LessonStep withSelectedVisuals(LessonStep step, TeachingLessonModel.StepDraft draft,
            TeachingLessonModel.SectionRequest request) {
        Map<String, TeachingLessonModel.VisualInput> offered = request.visuals().stream()
                .collect(Collectors.toMap(TeachingLessonModel.VisualInput::imageId, Function.identity(), (a, b) -> a));
        var blocks = draft.content().stream().map(block -> {
            var figures = block.figures().stream().map(figure -> {
                var visual = offered.get(figure.imageId());
                if (visual == null || !visual.evidenceIds().stream().anyMatch(step.sourceChunkIds()::contains)
                        || !step.sourcePages().contains(visual.focus().pageNumber())) {
                    throw new IllegalArgumentException("reading figure references an image outside the step evidence scope: " + figure.imageId());
                }
                return new com.rulepilot.teaching.domain.IllustratedLesson.ReadingFigure(visual.focus(), figure.caption());
            }).toList();
            return new com.rulepilot.teaching.domain.IllustratedLesson.ReadingBlock(
                    block.kind(), block.text(), figures, block.flow());
        }).toList();
        return step.withReadingBlocks(blocks);
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
    }
}
