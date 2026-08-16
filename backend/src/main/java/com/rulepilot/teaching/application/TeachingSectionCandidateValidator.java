package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashSet;
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
 * Converts one normalized, untrusted model draft into a fully cited lesson section.
 *
 * <p>This is the only point where draft structure, visual scope, and every player-facing claim are accepted
 * together. It performs no retrieval, model call, or publication.</p>
 */
final class TeachingSectionCandidateValidator {

    private static final Logger log = LoggerFactory.getLogger(TeachingSectionCandidateValidator.class);

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
        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        SectionDraft acceptedDraft = draft;
        validateStructureAndVisuals(acceptedDraft, modelRequest, evidence, allowedEvidence);
        List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(acceptedDraft, allowedEvidence);
        List<Claim> reviewClaims = LessonDraftValidator.reviewClaims(acceptedDraft, visualCitationIds);
        TeachingQuantitativeClaimPolicy.validate(planned, acceptedDraft, reviewClaims, allowedEvidence);
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
        List<LessonStep> steps = IntStream.range(0, acceptedDraft.steps().size())
                .mapToObj(index -> LessonDraftValidator.validatedStep(
                        index + 1, acceptedDraft.steps().get(index), allowedEvidence))
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
                acceptedDraft.title(),
                planned.required(),
                evidenceStatus,
                acceptedDraft.visualKind(),
                acceptedDraft.visualCaption(),
                visualSourcePages,
                visualCitationIds,
                steps);
    }

    /** Returns field-specific repair data without changing any player-facing field. */
    String repairDiagnostic(IllegalArgumentException rejection, SectionDraft draft) {
        return repairDiagnostic(rejection, draft, List.of());
    }

    String repairDiagnostic(
            IllegalArgumentException rejection,
            SectionDraft draft,
            List<RuleEvidence> availableEvidence) {
        if (rejection instanceof TeachingQuantitativeClaimPolicy.UnsupportedQuantitativeClaimException quantity) {
            List<Claim> claims = LessonDraftValidator.reviewClaims(draft, draft.visualCitationIds());
            Claim failedClaim = claims.stream()
                    .filter(claim -> claim.position() == quantity.claimPosition())
                    .findFirst()
                    .orElse(null);
            if (failedClaim == null) return rejection.getMessage();
            String field = quantity.claimPosition() == 1
                    ? "visualCaption"
                    : draft.steps().stream()
                            .filter(step -> failedClaim.text().equals(step.heading() + "：" + step.text()))
                            .map(step -> "step heading '" + step.heading() + "'")
                            .findFirst()
                            .orElse("claim position " + quantity.claimPosition());
            List<UUID> quantityOnlyCandidates = quantity.claimNumbers().isEmpty()
                    ? List.of()
                    : availableEvidence.stream()
                            .filter(source -> TeachingQuantitativeClaimPolicy.numbers(source.excerpt())
                                    .containsAll(quantity.claimNumbers()))
                            .map(RuleEvidence::chunkId)
                            .toList();
            String candidates = quantityOnlyCandidates.isEmpty()
                    ? ""
                    : " Quantity-only candidate citation IDs containing every number in this claim are "
                            + quantityOnlyCandidates
                            + "; inspect their excerpts and use one only if it directly supports the whole claim.";
            return rejection.getMessage() + ". The failing field is " + field
                    + " and its current citation IDs are " + failedClaim.citationIds()
                    + "." + candidates
                    + ". Repair only that field by citing supplied evidence that directly contains every quantity, "
                    + "or by rewriting only its unsupported quantitative claim from supplied evidence.";
        }
        if (rejection instanceof TeachingPlannedUnitCoveragePolicy.MissingDirectUnitEvidenceException unitFailure) {
            List<String> candidateHeadings = draft.steps().stream()
                    .filter(step -> step.teachingUnitIds().contains(unitFailure.unitId()))
                    .map(StepDraft::heading)
                    .toList();
            return rejection.getMessage() + ". The affected planned unit is '" + unitFailure.unitId()
                    + "'; its current step headings are " + candidateHeadings
                    + ". At least one of only those steps must add one of the direct citation IDs "
                    + unitFailure.directEvidenceIds()
                    + " for source identifier '" + unitFailure.sourceIdentifier()
                    + "'. Preserve all other steps and existing citations.";
        }
        return rejection.getMessage();
    }

    /**
     * Merges one Agent repair without allowing it to rewrite fields that already passed the deterministic boundary.
     * Every retained string is an exact field from either the original structured response or the repaired structured
     * response; this method never edits, truncates, filters sentences, or synthesizes player-facing prose.
     */
    SectionDraft mergeRepairPreservingValidatedFields(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest request,
            SectionDraft original,
            SectionDraft repaired) {
        if (original == null || repaired == null || original.steps().isEmpty() || !request.pageImages().isEmpty()) {
            return repaired;
        }
        List<Boolean> validOriginalSteps = new java.util.ArrayList<>();
        for (StepDraft step : original.steps()) {
            Set<String> stepUnitIds = new LinkedHashSet<>(step.teachingUnitIds());
            List<TeachingLessonModel.TeachingUnitInput> stepUnits = request.teachingUnits().stream()
                    .filter(unit -> stepUnitIds.contains(unit.unitId()))
                    .toList();
            try {
                validate(
                        plan,
                        planned,
                        evidence,
                        withTeachingUnits(request, stepUnits),
                        withSteps(original, List.of(step)),
                        EvidenceStatus.CITED_DRAFT);
                validOriginalSteps.add(true);
            } catch (IllegalArgumentException localFailure) {
                validOriginalSteps.add(false);
            }
        }
        if (validOriginalSteps.stream().noneMatch(Boolean.TRUE::equals)) return repaired;

        boolean[] usedRepairedSteps = new boolean[repaired.steps().size()];
        List<StepDraft> merged = new java.util.ArrayList<>();
        for (int index = 0; index < original.steps().size(); index++) {
            StepDraft originalStep = original.steps().get(index);
            int repairedIndex = matchingRepairedStep(originalStep, index, repaired.steps(), usedRepairedSteps);
            if (validOriginalSteps.get(index)) {
                merged.add(originalStep);
                if (repairedIndex >= 0) usedRepairedSteps[repairedIndex] = true;
            } else if (repairedIndex >= 0) {
                merged.add(repaired.steps().get(repairedIndex));
                usedRepairedSteps[repairedIndex] = true;
            }
        }
        for (int index = 0; index < repaired.steps().size(); index++) {
            if (!usedRepairedSteps[index]) merged.add(repaired.steps().get(index));
        }
        log.info(
                "Teaching topic {} repair preserved {} already validated player-facing steps byte-for-byte",
                planned.topicKey(),
                validOriginalSteps.stream().filter(Boolean.TRUE::equals).count());
        return new SectionDraft(
                original.title(),
                original.visualKind(),
                original.visualCaption(),
                original.visualCitationIds(),
                merged);
    }

    private int matchingRepairedStep(
            StepDraft original,
            int originalIndex,
            List<StepDraft> repaired,
            boolean[] used) {
        if (!original.teachingUnitIds().isEmpty()) {
            for (int index = 0; index < repaired.size(); index++) {
                if (!used[index]
                        && new LinkedHashSet<>(repaired.get(index).teachingUnitIds())
                                .equals(new LinkedHashSet<>(original.teachingUnitIds()))) {
                    return index;
                }
            }
        }
        return originalIndex < repaired.size() && !used[originalIndex] ? originalIndex : -1;
    }

    private SectionDraft withSteps(SectionDraft original, List<StepDraft> steps) {
        return new SectionDraft(
                original.title(),
                original.visualKind(),
                original.visualCaption(),
                original.visualCitationIds(),
                steps);
    }

    private TeachingLessonModel.SectionRequest withTeachingUnits(
            TeachingLessonModel.SectionRequest request,
            List<TeachingLessonModel.TeachingUnitInput> teachingUnits) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.priorSections(),
                request.evidence(),
                request.pageImages(),
                request.requiredRuleIntents(),
                teachingUnits,
                request.modelConfigurationOwner(),
                request.chapterScope(),
                request.wholeGameContext());
    }

    private void validateStructureAndVisuals(
            SectionDraft draft,
            TeachingLessonModel.SectionRequest request,
            List<RuleEvidence> evidence,
            Map<UUID, RuleEvidence> allowedEvidence) {
        LessonDraftValidator.validateDraft(draft, request);
        TeachingPlannedUnitCoveragePolicy.validate(request.teachingUnits(), evidence, draft);
        LessonDraftValidator.validateVisualBlockEvidence(draft, request, allowedEvidence);
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
    }
}
