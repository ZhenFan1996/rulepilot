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
            List<RulePageImage> pageImages) {

        public RuleEvidence(
                UUID chunkId,
                UUID documentVersionId,
                String sectionType,
                String heading,
                String excerpt,
                int pageFrom,
                int pageTo) {
            this(chunkId, documentVersionId, sectionType, heading, excerpt, pageFrom, pageTo, List.of());
        }

        public RuleEvidence {
            pageImages = pageImages == null ? List.of() : List.copyOf(pageImages);
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
