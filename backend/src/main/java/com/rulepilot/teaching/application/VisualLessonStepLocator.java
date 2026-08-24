package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Grounds cited lesson steps in one typed, page- and evidence-owned visual plan. */
final class VisualLessonStepLocator {

    private final DocumentPageImages pageImages;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionLocator locator;
    private final VisualReaderCropPolicy cropPolicy;

    VisualLessonStepLocator(
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualReaderCropPolicy cropPolicy) {
        this.pageImages = pageImages;
        this.candidates = candidates;
        this.locator = locator;
        this.cropPolicy = cropPolicy;
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return locator.supportsVisualEvidence(modelConfigurationOwner);
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner,
            int visualBudget) {
        return locate(
                understanding,
                documentVersionId,
                section,
                steps,
                modelConfigurationOwner,
                null,
                visualBudget);
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            List<LessonStep> steps,
            String modelConfigurationOwner,
            UUID runId,
            int visualBudget) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("visual lesson steps are required");
        }
        Set<Integer> citedPages = steps.stream()
                .flatMap(step -> step.sourcePages().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding,
                citedPages,
                terms(section, steps),
                visualBudget);
        if (selected.isEmpty()) return Result.rejected(VisualLessonEnricher.Outcome.NO_CITED_CANDIDATE);
        List<Integer> candidatePageOrder = selected.stream()
                .map(VisualRegionCandidateSelector.Candidate::pageNumber)
                .distinct()
                .toList();
        Map<Integer, DocumentPageImages.PageImage> availablePages = pageImages.read(
                        documentVersionId, new LinkedHashSet<>(candidatePageOrder))
                .stream()
                .collect(Collectors.toMap(
                        DocumentPageImages.PageImage::pageNumber, image -> image, (first, ignored) -> first));
        List<PageImage> pages = candidatePageOrder.stream()
                .map(availablePages::get)
                .filter(java.util.Objects::nonNull)
                .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        if (pages.isEmpty()) return Result.rejected(VisualLessonEnricher.Outcome.NO_PAGE_IMAGE);
        Set<Integer> attachedPages = pages.stream().map(PageImage::pageNumber).collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(steps);
        var guide = locator.locateGuideWithResult(new VisualRegionLocator.VisualLocationRequest(
                section.title(),
                claims,
                attachedCandidates,
                pages,
                modelConfigurationOwner,
                runId == null ? null : documentVersionId,
                runId,
                visualBudget));
        if (guide.regions().isEmpty()) return Result.rejected(outcomeFor(guide.diagnostic()));
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        List<VisualRegionLocator.LocatedRegion> accepted = new ArrayList<>();
        VisualLessonEnricher.Outcome rejected = null;
        for (VisualRegionLocator.LocatedRegion candidate : guide.regions().stream().limit(visualBudget).toList()) {
            VisualRegionLocator.LocatedRegion region = candidate;
            if (cropPolicy.needsReaderViewport(region)) {
                if (!cropPolicy.canExpandIntoReaderViewport(region)) {
                    rejected = VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL;
                    continue;
                }
                region = cropPolicy.expandIntoReaderViewport(region);
            }
            VisualLessonEnricher.Outcome rejection = rejectionFor(region, attachedCandidates, evidenceIds);
            if (rejection == null && !supportsExactStep(region, steps)) {
                rejection = VisualLessonEnricher.Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null) accepted.add(region);
            else rejected = rejection;
        }
        if (!accepted.isEmpty()) return Result.accepted(accepted);
        return Result.rejected(rejected == null ? VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE : rejected);
    }

    private List<String> terms(LessonSection section, List<LessonStep> steps) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        steps.forEach(step -> {
            result.add(step.heading());
            result.add(step.text());
        });
        return List.copyOf(result);
    }

    private List<Claim> claims(List<LessonStep> steps) {
        return steps.stream()
                .flatMap(step -> new LinkedHashSet<>(step.sourceChunkIds()).stream()
                        .map(id -> new Claim(id, claimText(step), step.sourcePages(), step.position())))
                .toList();
    }

    private String claimText(LessonStep step) {
        return "步骤 " + step.position() + "（" + step.heading() + "）：" + step.text();
    }

    private boolean supportsExactStep(VisualRegionLocator.LocatedRegion region, List<LessonStep> steps) {
        if (region.supportedStepPositions().isEmpty()) return steps.size() == 1;
        Set<Integer> offeredPositions = steps.stream().map(LessonStep::position).collect(Collectors.toSet());
        return region.supportedStepPositions().stream().allMatch(offeredPositions::contains);
    }

    private VisualLessonEnricher.Outcome rejectionFor(
            VisualRegionLocator.LocatedRegion region,
            List<VisualRegionCandidateSelector.Candidate> attachedCandidates,
            Set<UUID> evidenceIds) {
        if (!cropPolicy.isReadableForPlayer(region)) return VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL;
        if (region.visibleDescription().isBlank()) return VisualLessonEnricher.Outcome.REJECTED_MISSING_OBSERVATION;
        if (!cropPolicy.isUsefulPlayerVisual(region)) return VisualLessonEnricher.Outcome.REJECTED_NON_VISUAL;
        if (!cropPolicy.intersectsCandidate(region, attachedCandidates)) return VisualLessonEnricher.Outcome.REJECTED_OUTSIDE_CANDIDATE;
        if (!evidenceIds.containsAll(region.supportedEvidenceIds())) return VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE;
        return null;
    }

    private VisualLessonEnricher.Outcome outcomeFor(VisualRegionLocator.Diagnostic diagnostic) {
        return switch (diagnostic) {
            case NO_REGION -> VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE;
            case SEMANTIC_REJECTED -> VisualLessonEnricher.Outcome.MODEL_SEMANTIC_REJECTED;
            case MODEL_UNAVAILABLE -> VisualLessonEnricher.Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> VisualLessonEnricher.Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> VisualLessonEnricher.Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> VisualLessonEnricher.Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> VisualLessonEnricher.Outcome.MODEL_INVALID_GEOMETRY;
            case TIMEOUT -> VisualLessonEnricher.Outcome.MODEL_TIMEOUT;
            case INTERRUPTED -> VisualLessonEnricher.Outcome.MODEL_INTERRUPTED;
            case EXECUTOR_BUSY -> VisualLessonEnricher.Outcome.MODEL_BUSY;
            case PROVIDER_FAILURE -> VisualLessonEnricher.Outcome.MODEL_PROVIDER_FAILURE;
            case FOUND -> throw new IllegalArgumentException("found visual location cannot be rejected");
        };
    }

    record Result(List<VisualRegionLocator.LocatedRegion> regions, VisualLessonEnricher.Outcome rejection) {
        Result {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }

        static Result accepted(List<VisualRegionLocator.LocatedRegion> regions) {
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("accepted visual lesson plan needs at least one region");
            }
            return new Result(regions, null);
        }

        static Result rejected(VisualLessonEnricher.Outcome rejection) {
            return new Result(List.of(), rejection);
        }
    }
}
