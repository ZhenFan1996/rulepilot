package com.rulepilot.teaching;

import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.List;
import java.util.Optional;
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
        PROVIDER_FAILURE
    }

    /** Typed visual review outcomes; only ACCEPT and USE_FULL_PAGE may cross the publication boundary. */
    enum ReviewAction {
        ACCEPT,
        RECROP,
        USE_FULL_PAGE,
        REJECT
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

    record LocateGuideResult(List<LocatedRegion> regions, Diagnostic diagnostic) {
        public LocateGuideResult {
            if (regions == null || regions.size() > 12 || diagnostic == null
                    || (regions.isEmpty() && diagnostic == Diagnostic.FOUND)
                    || (!regions.isEmpty() && diagnostic != Diagnostic.FOUND)) {
                throw new IllegalArgumentException("visual guide result is invalid");
            }
            regions = List.copyOf(regions);
        }

        public static LocateGuideResult found(List<LocatedRegion> regions) {
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("a found visual guide needs at least one region");
            }
            return new LocateGuideResult(regions, Diagnostic.FOUND);
        }

        public static LocateGuideResult unavailable(Diagnostic diagnostic) {
            if (diagnostic == null || diagnostic == Diagnostic.FOUND) {
                throw new IllegalArgumentException("visual guide diagnostic is invalid");
            }
            return new LocateGuideResult(List.of(), diagnostic);
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
            int visualBudget) {
        public static final int DEFAULT_VISUAL_BUDGET = 6;

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
                    DEFAULT_VISUAL_BUDGET);
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
                    DEFAULT_VISUAL_BUDGET);
        }

        public VisualLocationRequest {
            if (sectionTitle == null || sectionTitle.isBlank() || claims == null || claims.isEmpty()
                    || candidates == null || candidates.isEmpty()
                    || pages == null || pages.isEmpty()
                    || visualBudget < 1 || visualBudget > 12
                    || candidates.size() > visualBudget
                    || pages.size() > visualBudget) {
                throw new IllegalArgumentException("visual location request is invalid");
            }
            claims = List.copyOf(claims);
            candidates = List.copyOf(candidates);
            pages = List.copyOf(pages);
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
                    DEFAULT_VISUAL_BUDGET);
        }
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
