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
        LessonDraftValidator.validateDraft(draft, modelRequest);

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        LessonDraftValidator.validateVisualBlockEvidence(draft, modelRequest, allowedEvidence);
        List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence);
        LessonDraftValidator.validatePlayerCountConditionalValues(draft, allowedEvidence);
        LessonDraftValidator.validatePlayerCountConditionalScopes(draft, allowedEvidence);
        LessonDraftValidator.validateSharedTieResolution(draft, allowedEvidence);
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

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
    }
}
