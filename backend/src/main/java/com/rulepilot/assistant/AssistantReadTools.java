package com.rulepilot.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AssistantReadTools {

    List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request);

    default List<RuleEvidence> readRuleEvidencePages(
            UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
        return List.of();
    }

    default List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
        return List.of();
    }

    default RuleEvidenceContext readRuleEvidenceContext(
            UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius) {
        return new RuleEvidenceContext(List.of(), List.of());
    }

    record SearchRuleEvidence(
            UUID documentVersionId,
            String query,
            int limit,
            Set<String> sectionTypes,
            String currentSectionType,
            boolean includeAdjacentContext,
            boolean includePageImages) {

        public SearchRuleEvidence(
                UUID documentVersionId,
                String query,
                int limit,
                Set<String> sectionTypes,
                String currentSectionType,
                boolean includeAdjacentContext) {
            this(documentVersionId, query, limit, sectionTypes, currentSectionType, includeAdjacentContext, false);
        }

        public SearchRuleEvidence(
                UUID documentVersionId,
                String query,
                int limit,
                Set<String> sectionTypes,
                String currentSectionType) {
            this(documentVersionId, query, limit, sectionTypes, currentSectionType, false, false);
        }
    }

    record RuleEvidence(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo,
            List<RulePageImage> pageImages,
            ContentKind contentKind) {

        public enum ContentKind {
            CANONICAL_TEXT,
            VISUAL_PLACEHOLDER,
            CANONICAL_TEXT_WITH_VISUAL_FACTS,
            VISUAL_TRANSCRIPTION
        }

        public RuleEvidence(
                UUID chunkId,
                UUID documentVersionId,
                String sectionType,
                String heading,
                String excerpt,
                int pageFrom,
                int pageTo,
                List<RulePageImage> pageImages) {
            this(
                    chunkId,
                    documentVersionId,
                    sectionType,
                    heading,
                    excerpt,
                    pageFrom,
                    pageTo,
                    pageImages,
                    ContentKind.CANONICAL_TEXT);
        }

        public RuleEvidence(
                UUID chunkId,
                UUID documentVersionId,
                String sectionType,
                String heading,
                String excerpt,
                int pageFrom,
                int pageTo) {
            this(
                    chunkId,
                    documentVersionId,
                    sectionType,
                    heading,
                    excerpt,
                    pageFrom,
                    pageTo,
                    List.of(),
                    ContentKind.CANONICAL_TEXT);
        }

        public RuleEvidence {
            pageImages = pageImages == null ? List.of() : List.copyOf(pageImages);
            if (contentKind == null) throw new IllegalArgumentException("rule evidence content kind is required");
        }
    }

    record RuleEvidenceContext(
            List<RuleEvidence> anchors,
            List<RuleEvidence> surroundingEvidence) {
        public RuleEvidenceContext {
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
            surroundingEvidence = surroundingEvidence == null ? List.of() : List.copyOf(surroundingEvidence);
        }
    }

    record RulePageImage(int pageNumber, String mediaType, byte[] content, int width, int height) {
        public RulePageImage {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0
                    || width < 1 || height < 1) {
                throw new IllegalArgumentException("rule page image is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
