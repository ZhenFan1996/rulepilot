package com.rulepilot.teaching;

import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A vision-only port: it may locate a cited region but can never compose lesson prose. */
public interface VisualRegionLocator {

    Optional<LocatedRegion> locate(VisualLocationRequest request);

    /**
     * Keeps optional visual work observable without asking the lesson writer to infer why no crop was attached.
     * Existing lightweight adapters only need to implement {@link #locate(VisualLocationRequest)}.
     */
    default LocateResult locateWithResult(VisualLocationRequest request) {
        Optional<LocatedRegion> region = locate(request);
        return region.map(LocateResult::found).orElseGet(LocateResult::noRegion);
    }

    enum Diagnostic {
        FOUND,
        NO_REGION,
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

    record VisualLocationRequest(
            String sectionTitle,
            List<Claim> claims,
            List<Candidate> candidates,
            List<PageImage> pages,
            String modelConfigurationOwner) {
        public VisualLocationRequest {
            if (sectionTitle == null || sectionTitle.isBlank() || claims == null || claims.isEmpty()
                    || candidates == null || candidates.isEmpty() || candidates.size() > 4
                    || pages == null || pages.isEmpty() || pages.size() > 2) {
                throw new IllegalArgumentException("visual location request is invalid");
            }
            claims = List.copyOf(claims);
            candidates = List.copyOf(candidates);
            pages = List.copyOf(pages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }

        public VisualLocationRequest(
                String sectionTitle, List<Claim> claims, List<Candidate> candidates, List<PageImage> pages) {
            this(sectionTitle, claims, candidates, pages, null);
        }
    }

    record Claim(UUID evidenceId, String text) {
        public Claim {
            if (evidenceId == null || text == null || text.isBlank() || text.length() > 600) {
                throw new IllegalArgumentException("visual claim is invalid");
            }
            text = text.strip();
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
            List<UUID> supportedEvidenceIds) {
        public LocatedRegion {
            if (pageNumber < 1 || label == null || label.isBlank() || label.length() > 80
                    || (visibleDescription != null && visibleDescription.length() > 240)
                    || x < 0 || y < 0 || width < 20 || height < 20 || x + width > 1_000 || y + height > 1_000
                    || supportedEvidenceIds == null || supportedEvidenceIds.isEmpty()
                    || supportedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("located visual region is invalid");
            }
            label = label.strip();
            visibleDescription = visibleDescription == null ? "" : visibleDescription.strip();
            supportedEvidenceIds = List.copyOf(supportedEvidenceIds);
        }

        public LocatedRegion(
                int pageNumber,
                String label,
                int x,
                int y,
                int width,
                int height,
                List<UUID> supportedEvidenceIds) {
            this(pageNumber, label, "", x, y, width, height, supportedEvidenceIds);
        }
    }
}
