package com.rulepilot.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canonical visual evidence reached only through a text-evidence handle from the active document version. */
public interface NativeVisualEvidence {

    Optional<VisualPage> readPage(UUID documentVersionId, UUID evidenceId, int pageNumber);

    Optional<VisualCrop> cropPage(
            UUID documentVersionId,
            UUID evidenceId,
            int pageNumber,
            int x,
            int y,
            int width,
            int height);

    List<VisualPageFact> readPageFacts(UUID documentVersionId, UUID evidenceId, int pageNumber);

    record VisualPage(
            UUID evidenceId,
            int pageNumber,
            String mediaType,
            byte[] content,
            int width,
            int height) {
        public VisualPage {
            if (evidenceId == null || pageNumber < 1 || mediaType == null || mediaType.isBlank()
                    || content == null || content.length == 0 || width < 1 || height < 1) {
                throw new IllegalArgumentException("native visual page is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record VisualCrop(
            UUID evidenceId,
            int pageNumber,
            String mediaType,
            byte[] content,
            int x,
            int y,
            int width,
            int height,
            int pixelWidth,
            int pixelHeight) {
        public VisualCrop {
            if (evidenceId == null || pageNumber < 1 || !"image/jpeg".equals(mediaType)
                    || content == null || content.length == 0
                    || x < 0 || y < 0 || width < 12 || height < 12
                    || x + width > 1_000 || y + height > 1_000
                    || pixelWidth < 1 || pixelHeight < 1) {
                throw new IllegalArgumentException("native visual crop is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record VisualPageFact(
            int pageNumber,
            String printedTerms,
            String literalSummary,
            List<VisualAnchor> anchors,
            List<VisualIcon> icons) {
        public VisualPageFact {
            if (pageNumber < 1 || printedTerms == null || literalSummary == null || anchors == null || icons == null
                    || printedTerms.length() > 2_000 || literalSummary.length() > 4_000
                    || anchors.size() > 8 || icons.size() > 32) {
                throw new IllegalArgumentException("native visual page fact is invalid");
            }
            printedTerms = printedTerms.strip();
            literalSummary = literalSummary.strip();
            anchors = List.copyOf(anchors);
            icons = List.copyOf(icons);
        }
    }

    record VisualAnchor(String kind, String label, String visibleDescription, int x, int y, int width, int height) {}

    record VisualIcon(
            String name,
            String visualDescription,
            String meaningStatus,
            String visibleEvidence,
            int x,
            int y,
            int width,
            int height) {}
}
