package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Grounds one cited lesson step in a compact, player-readable rulebook crop. */
final class VisualLessonStepLocator {

    private final DocumentPageImages pageImages;
    private final VisualRulebookPageFacts visualPageFacts;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionLocator locator;
    private final VisualReaderCropPolicy cropPolicy;
    private final VisualStepRelevancePolicy stepRelevancePolicy;

    VisualLessonStepLocator(
            DocumentPageImages pageImages,
            VisualRulebookPageFacts visualPageFacts,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualReaderCropPolicy cropPolicy,
            VisualStepRelevancePolicy stepRelevancePolicy) {
        this.pageImages = pageImages;
        this.visualPageFacts = visualPageFacts;
        this.candidates = candidates;
        this.locator = locator;
        this.cropPolicy = cropPolicy;
        this.stepRelevancePolicy = stepRelevancePolicy;
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner) {
        return locate(understanding, documentVersionId, section, step, modelConfigurationOwner, null);
    }

    Result locate(
            RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner,
            UUID runId) {
        Set<Integer> citedPages = new LinkedHashSet<>(step.sourcePages());
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section, step), visualPageFacts.find(documentVersionId, citedPages));
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
                .limit(2)
                .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        if (pages.isEmpty()) return Result.rejected(VisualLessonEnricher.Outcome.NO_PAGE_IMAGE);
        Set<Integer> attachedPages = pages.stream().map(PageImage::pageNumber).collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(step);
        var guide = locator.locateGuideWithResult(new VisualRegionLocator.VisualLocationRequest(
                section.title() + " · " + step.heading(),
                claims,
                attachedCandidates,
                pages,
                modelConfigurationOwner,
                runId == null ? null : documentVersionId,
                runId));
        if (guide.regions().isEmpty()) return Result.rejected(outcomeFor(guide.diagnostic()));
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        VisualLessonEnricher.Outcome rejected = null;
        for (VisualRegionLocator.LocatedRegion candidate : guide.regions()) {
            VisualRegionLocator.LocatedRegion region = candidate;
            if (cropPolicy.needsReaderViewport(region)) {
                if (!cropPolicy.canExpandIntoReaderViewport(region)) {
                    rejected = VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL;
                    continue;
                }
                region = cropPolicy.expandIntoReaderViewport(region);
            }
            VisualLessonEnricher.Outcome rejection = rejectionFor(region, attachedCandidates, evidenceIds);
            if (rejection == null && !supportsExactStep(region, step)) {
                rejection = VisualLessonEnricher.Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null && !stepRelevancePolicy.directlyIllustrates(step, region)) {
                rejection = VisualLessonEnricher.Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null) return Result.accepted(region);
            rejected = rejection;
        }
        return Result.rejected(rejected == null ? VisualLessonEnricher.Outcome.REJECTED_UNKNOWN_EVIDENCE : rejected);
    }

    private List<String> terms(LessonSection section, LessonStep step) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        result.add(step.heading());
        result.add(step.text());
        return List.copyOf(result);
    }

    private List<Claim> claims(LessonStep step) {
        return new LinkedHashSet<>(step.sourceChunkIds()).stream()
                .map(id -> new Claim(id, claimText(step), step.sourcePages(), step.position()))
                .toList();
    }

    private String claimText(LessonStep step) {
        String prefix = "步骤 " + step.position() + "（" + step.heading() + "）：";
        int remaining = 600 - prefix.length();
        if (remaining <= 0) return prefix.substring(0, 600);
        String body = step.text();
        return body.length() <= remaining ? prefix + body : prefix + body.substring(0, remaining);
    }

    private boolean supportsExactStep(VisualRegionLocator.LocatedRegion region, LessonStep step) {
        return region.supportedStepPositions().isEmpty() || region.supportedStepPositions().contains(step.position());
    }

    private VisualLessonEnricher.Outcome rejectionFor(
            VisualRegionLocator.LocatedRegion region,
            List<VisualRegionCandidateSelector.Candidate> attachedCandidates,
            Set<UUID> evidenceIds) {
        if (!cropPolicy.isCompactReaderCrop(region)) return VisualLessonEnricher.Outcome.REJECTED_WHOLE_PAGE;
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
            case OVERSIZED_REGION -> VisualLessonEnricher.Outcome.MODEL_OVERSIZED_REGION;
            case SEMANTIC_REJECTED -> VisualLessonEnricher.Outcome.MODEL_SEMANTIC_REJECTED;
            case MODEL_UNAVAILABLE -> VisualLessonEnricher.Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> VisualLessonEnricher.Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> VisualLessonEnricher.Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> VisualLessonEnricher.Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> VisualLessonEnricher.Outcome.MODEL_INVALID_GEOMETRY;
            case NON_CHINESE_OBSERVATION -> VisualLessonEnricher.Outcome.MODEL_NON_CHINESE_OBSERVATION;
            case TIMEOUT -> VisualLessonEnricher.Outcome.MODEL_TIMEOUT;
            case INTERRUPTED -> VisualLessonEnricher.Outcome.MODEL_INTERRUPTED;
            case EXECUTOR_BUSY -> VisualLessonEnricher.Outcome.MODEL_BUSY;
            case PROVIDER_FAILURE -> VisualLessonEnricher.Outcome.MODEL_PROVIDER_FAILURE;
            case FOUND -> throw new IllegalArgumentException("found visual location cannot be rejected");
        };
    }

    record Result(VisualRegionLocator.LocatedRegion region, VisualLessonEnricher.Outcome rejection) {
        static Result accepted(VisualRegionLocator.LocatedRegion region) {
            return new Result(region, null);
        }

        static Result rejected(VisualLessonEnricher.Outcome rejection) {
            return new Result(null, rejection);
        }
    }
}
