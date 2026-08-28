package com.rulepilot.teaching;

import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A vision-only port: it may locate a cited region but can never compose lesson prose. */
public interface VisualRegionLocator {

    Optional<LocatedRegion> locate(VisualLocationRequest request);

    /** Lets orchestration omit visual-only work when an owner has no image-capable model configured. */
    default boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return true;
    }

    /**
     * Keeps optional visual work observable without asking the lesson writer to infer why no crop was attached.
     * Existing lightweight adapters only need to implement {@link #locate(VisualLocationRequest)}.
     */
    default LocateResult locateWithResult(VisualLocationRequest request) {
        Optional<LocatedRegion> region = locate(request);
        return region.map(LocateResult::found).orElseGet(LocateResult::noRegion);
    }

    /**
     * A visual walkthrough may need a small icon legend and a separate worked state. The default keeps legacy
     * locators compatible while vision-capable adapters can return both independently grounded anchors in one call.
     */
    default LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
        LocateResult result = locateWithResult(request);
        return result.region()
                .map(region -> LocateGuideResult.found(List.of(region)))
                .orElseGet(() -> LocateGuideResult.unavailable(result.diagnostic()));
    }

    /**
     * Lets the application shorten one transport call to the remaining workflow wall-time. The timeout is a resource
     * boundary only: it never limits how many finite candidate batches the Agent may choose to inspect.
     */
    default LocateGuideResult locateGuideWithResult(VisualLocationRequest request, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("visual location timeout must be positive");
        }
        return locateGuideWithResult(request);
    }

    enum Diagnostic {
        FOUND,
        NO_REGION,
        SEMANTIC_REJECTED,
        MODEL_UNAVAILABLE,
        EXPLICIT_NO_REGION,
        MALFORMED_RESPONSE,
        UNSUPPORTED_SCOPE,
        INVALID_GEOMETRY,
        TIMEOUT,
        INTERRUPTED,
        EXECUTOR_BUSY,
        PROVIDER_FAILURE,
        CANDIDATE_PREPARATION_FAILED
    }

    /** The model decides whether inspecting another finite attachment batch is worth another bounded call. */
    enum BatchAction {
        STOP,
        CONTINUE
    }

    record LocateResult(Optional<LocatedRegion> region, Diagnostic diagnostic) {
        public LocateResult {
            if (region == null || diagnostic == null
                    || (region.isPresent() && diagnostic != Diagnostic.FOUND)
                    || (region.isEmpty() && diagnostic == Diagnostic.FOUND)) {
                throw new IllegalArgumentException("visual location result is invalid");
            }
        }

        public static LocateResult found(LocatedRegion region) {
            return new LocateResult(Optional.ofNullable(region), Diagnostic.FOUND);
        }

        public static LocateResult noRegion() {
            return new LocateResult(Optional.empty(), Diagnostic.NO_REGION);
        }

        public static LocateResult unavailable(Diagnostic diagnostic) {
            if (diagnostic == null || diagnostic == Diagnostic.FOUND || diagnostic == Diagnostic.NO_REGION) {
                throw new IllegalArgumentException("visual location diagnostic is invalid");
            }
            return new LocateResult(Optional.empty(), diagnostic);
        }
    }

    record LocateGuideResult(List<LocatedRegion> regions, Diagnostic diagnostic, BatchAction batchAction) {
        public LocateGuideResult {
            if (regions == null
                    || regions.size() > VisualLocationRequest.MAX_CANDIDATES_PER_BATCH
                    || diagnostic == null
                    || batchAction == null
                    || (regions.isEmpty() && diagnostic == Diagnostic.FOUND)
                    || (!regions.isEmpty() && diagnostic != Diagnostic.FOUND)) {
                throw new IllegalArgumentException("visual guide result is invalid");
            }
            regions = List.copyOf(regions);
        }

        public static LocateGuideResult found(List<LocatedRegion> regions) {
            return found(regions, BatchAction.STOP);
        }

        public static LocateGuideResult found(List<LocatedRegion> regions, BatchAction batchAction) {
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("a found visual guide needs at least one region");
            }
            return new LocateGuideResult(regions, Diagnostic.FOUND, batchAction);
        }

        public static LocateGuideResult unavailable(Diagnostic diagnostic) {
            return unavailable(diagnostic, BatchAction.STOP);
        }

        public static LocateGuideResult unavailable(Diagnostic diagnostic, BatchAction batchAction) {
            if (diagnostic == null || diagnostic == Diagnostic.FOUND) {
                throw new IllegalArgumentException("visual guide diagnostic is invalid");
            }
            return new LocateGuideResult(List.of(), diagnostic, batchAction);
        }
    }

    record VisualLocationRequest(
            String sectionTitle,
            List<Claim> claims,
            List<Candidate> candidates,
            List<PageImage> pages,
            String modelConfigurationOwner,
            UUID documentVersionId,
            UUID runId,
            int batchNumber,
            boolean hasMoreCandidates) {
        public static final int MAX_CANDIDATES_PER_BATCH = 12;

        public VisualLocationRequest(
                String sectionTitle,
                List<Claim> claims,
                List<Candidate> candidates,
                List<PageImage> pages,
                String modelConfigurationOwner,
                UUID documentVersionId,
                UUID runId) {
            this(
                    sectionTitle,
                    claims,
                    candidates,
                    pages,
                    modelConfigurationOwner,
                    documentVersionId,
                    runId,
                    1,
                    false);
        }

        public VisualLocationRequest(
                String sectionTitle,
                List<Claim> claims,
                List<Candidate> candidates,
                List<PageImage> pages,
                String modelConfigurationOwner) {
            this(
                    sectionTitle,
                    claims,
                    candidates,
                    pages,
                    modelConfigurationOwner,
                    null,
                    null,
                    1,
                    false);
        }

        public VisualLocationRequest {
            if (sectionTitle == null || sectionTitle.isBlank() || claims == null || claims.isEmpty()
                    || candidates == null || candidates.isEmpty()
                    || pages == null || pages.isEmpty()
                    || candidates.size() > MAX_CANDIDATES_PER_BATCH
                    || pages.size() > MAX_CANDIDATES_PER_BATCH
                    || batchNumber < 1) {
                throw new IllegalArgumentException("visual location request is invalid");
            }
            claims = List.copyOf(claims);
            candidates = List.copyOf(candidates);
            pages = List.copyOf(pages);
            Set<String> candidateIds = candidates.stream()
                    .map(Candidate::candidateId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<CandidateBoundary> candidateBoundaries = candidates.stream()
                    .map(candidate -> new CandidateBoundary(
                            candidate.pageNumber(), candidate.rectangle(), candidate.sourceKind()))
                    .collect(java.util.stream.Collectors.toSet());
            Set<Integer> pageNumbers = pages.stream()
                    .map(PageImage::pageNumber)
                    .collect(java.util.stream.Collectors.toSet());
            if (candidateIds.size() != candidates.size()
                    || candidateBoundaries.size() != candidates.size()
                    || pageNumbers.size() != pages.size()
                    || candidates.stream().anyMatch(candidate -> !pageNumbers.contains(candidate.pageNumber()))) {
                throw new IllegalArgumentException("visual candidate attachments are invalid");
            }
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
            if ((documentVersionId == null) != (runId == null)) {
                throw new IllegalArgumentException("visual Agent context is incomplete");
            }
        }

        public VisualLocationRequest(
                String sectionTitle, List<Claim> claims, List<Candidate> candidates, List<PageImage> pages) {
            this(
                    sectionTitle,
                    claims,
                    candidates,
                    pages,
                    null,
                    null,
                    null,
                    1,
                    false);
        }

        private record CandidateBoundary(
                int pageNumber,
                com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle rectangle,
                VisualSourceKind sourceKind) {}
    }

    /**
     * A claim belongs to one concrete lesson step. Several steps may cite the same evidence chunk, so evidence alone
     * is intentionally not enough to decide where a visual aid is attached.
     */
    record Claim(UUID evidenceId, String text, List<Integer> sourcePages, int stepPosition) {
        public Claim {
            if (evidenceId == null || text == null || text.isBlank() || stepPosition < 0) {
                throw new IllegalArgumentException("visual claim is invalid");
            }
            text = text.strip();
            sourcePages = sourcePages == null ? List.of() : List.copyOf(sourcePages);
            if (sourcePages.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("visual claim pages are invalid");
            }
        }

        public Claim(UUID evidenceId, String text, List<Integer> sourcePages) {
            this(evidenceId, text, sourcePages, 0);
        }

        public Claim(UUID evidenceId, String text) {
            this(evidenceId, text, List.of(), 0);
        }
    }

    record PageImage(int pageNumber, String mediaType, byte[] content) {
        public PageImage {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0) {
                throw new IllegalArgumentException("visual page image is invalid");
            }
            content = content.clone();
        }

        @Override public byte[] content() { return content.clone(); }
    }

    /**
     * A bounded, literal observation of a page region. It is deliberately not a rule interpretation:
     * the associated text evidence remains the source for game-rule claims.
     */
    record LocatedRegion(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height,
            List<UUID> supportedEvidenceIds,
            List<Integer> supportedStepPositions,
            boolean claimContradicted,
            VisualSourceKind sourceKind) {
        public LocatedRegion {
            if (pageNumber < 1 || label == null || label.isBlank() || label.length() > 80
                    || (visibleDescription != null && visibleDescription.length() > 240)
                    || x < 0 || y < 0 || width < 20 || height < 20 || x + width > 1_000 || y + height > 1_000
                    || supportedEvidenceIds == null || supportedEvidenceIds.isEmpty()
                    || supportedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)
                    || supportedStepPositions == null
                    || supportedStepPositions.stream().anyMatch(position -> position == null || position < 1)
                    || sourceKind == null) {
                throw new IllegalArgumentException("located visual region is invalid");
            }
            boolean completePage = x == 0 && y == 0 && width == 1_000 && height == 1_000;
            if ((sourceKind == VisualSourceKind.FULL_PAGE) != completePage) {
                throw new IllegalArgumentException("full-page visual region kind and geometry must agree");
            }
            visibleDescription = visibleDescription == null ? "" : visibleDescription;
            supportedEvidenceIds = List.copyOf(supportedEvidenceIds);
            supportedStepPositions = List.copyOf(supportedStepPositions);
        }

        public LocatedRegion(
                int pageNumber,
                String label,
                String visibleDescription,
                int x,
                int y,
                int width,
                int height,
                List<UUID> supportedEvidenceIds,
                List<Integer> supportedStepPositions) {
            this(
                    pageNumber,
                    label,
                    visibleDescription,
                    x,
                    y,
                    width,
                    height,
                    supportedEvidenceIds,
                    supportedStepPositions,
                    false,
                    inferredSourceKind(x, y, width, height));
        }

        public LocatedRegion(
                int pageNumber,
                String label,
                String visibleDescription,
                int x,
                int y,
                int width,
                int height,
                List<UUID> supportedEvidenceIds,
                List<Integer> supportedStepPositions,
                boolean claimContradicted) {
            this(
                    pageNumber,
                    label,
                    visibleDescription,
                    x,
                    y,
                    width,
                    height,
                    supportedEvidenceIds,
                    supportedStepPositions,
                    claimContradicted,
                    inferredSourceKind(x, y, width, height));
        }

        public LocatedRegion(
                int pageNumber,
                String label,
                String visibleDescription,
                int x,
                int y,
                int width,
                int height,
                List<UUID> supportedEvidenceIds) {
            this(pageNumber, label, visibleDescription, x, y, width, height, supportedEvidenceIds, List.of(), false);
        }

        public LocatedRegion(
                int pageNumber,
                String label,
                int x,
                int y,
                int width,
                int height,
                List<UUID> supportedEvidenceIds) {
            this(pageNumber, label, "", x, y, width, height, supportedEvidenceIds, List.of(), false);
        }

        public LocatedRegion withClaimContradiction() {
            return new LocatedRegion(
                    pageNumber,
                    label,
                    visibleDescription,
                    x,
                    y,
                    width,
                    height,
                    supportedEvidenceIds,
                    supportedStepPositions,
                    true,
                    sourceKind);
        }

        private static VisualSourceKind inferredSourceKind(int x, int y, int width, int height) {
            return x == 0 && y == 0 && width == 1_000 && height == 1_000
                    ? VisualSourceKind.FULL_PAGE
                    : VisualSourceKind.PAGE_REGION;
        }
    }
}
