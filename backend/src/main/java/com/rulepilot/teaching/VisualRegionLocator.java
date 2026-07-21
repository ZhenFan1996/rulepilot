package com.rulepilot.teaching;

import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A vision-only port: it may locate a cited region but can never compose lesson prose. */
public interface VisualRegionLocator {

    Optional<LocatedRegion> locate(VisualLocationRequest request);

    record VisualLocationRequest(
            String sectionTitle,
            List<Claim> claims,
            List<Candidate> candidates,
            List<PageImage> pages) {
        public VisualLocationRequest {
            if (sectionTitle == null || sectionTitle.isBlank() || claims == null || claims.isEmpty()
                    || candidates == null || candidates.isEmpty() || candidates.size() > 4
                    || pages == null || pages.isEmpty() || pages.size() > 2) {
                throw new IllegalArgumentException("visual location request is invalid");
            }
            claims = List.copyOf(claims);
            candidates = List.copyOf(candidates);
            pages = List.copyOf(pages);
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

    record LocatedRegion(int pageNumber, String label, int x, int y, int width, int height, List<UUID> supportedEvidenceIds) {
        public LocatedRegion {
            if (pageNumber < 1 || label == null || label.isBlank() || label.length() > 80
                    || x < 0 || y < 0 || width < 20 || height < 20 || x + width > 1_000 || y + height > 1_000
                    || supportedEvidenceIds == null || supportedEvidenceIds.isEmpty()
                    || supportedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("located visual region is invalid");
            }
            label = label.strip();
            supportedEvidenceIds = List.copyOf(supportedEvidenceIds);
        }
    }
}
