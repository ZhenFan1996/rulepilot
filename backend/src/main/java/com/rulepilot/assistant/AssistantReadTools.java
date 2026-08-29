package com.rulepilot.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AssistantReadTools {

    List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request);

    default RuleEvidencePage searchRuleEvidencePage(SearchRuleEvidence request, int offset, int pageSize) {
        if (request == null || offset != 0 || pageSize < 1) {
            throw new IllegalArgumentException("rule evidence search page is invalid");
        }
        int requested = Math.min(request.limit(), pageSize);
        List<RuleEvidence> evidence = searchRuleEvidence(new SearchRuleEvidence(
                request.documentVersionId(), request.query(), requested, request.sectionTypes(),
                request.currentSectionType(), request.includeAdjacentContext(), request.includePageImages()));
        return new RuleEvidencePage(evidence, request.limit() > requested && evidence.size() == requested);
    }

    default RuleEvidencePage searchRuleEvidencePage(
            SearchRuleEvidence request, int offset, int pageSize, Set<UUID> excludedEvidenceIds) {
        if (excludedEvidenceIds == null || excludedEvidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("excluded rule evidence identities are invalid");
        }
        return searchRuleEvidencePage(request, offset, pageSize);
    }

    default List<RuleEvidence> readRuleEvidencePages(
            UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
        return List.of();
    }

    default RuleEvidencePage readRuleEvidencePagesPage(
            UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages, int offset, int pageSize) {
        if (offset != 0 || pageSize < 1) throw new IllegalArgumentException("rule evidence page window is invalid");
        return new RuleEvidencePage(
                readRuleEvidencePages(documentVersionId, pageNumbers, includePageImages), false);
    }

    default List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
        return List.of();
    }

    default RuleEvidencePage readRuleEvidenceIdsPage(
            UUID documentVersionId, Set<UUID> evidenceIds, int offset, int pageSize) {
        if (offset != 0 || pageSize < 1) throw new IllegalArgumentException("rule evidence id window is invalid");
        return new RuleEvidencePage(readRuleEvidenceIds(documentVersionId, evidenceIds), false);
    }

    default RuleEvidenceContext readRuleEvidenceContext(
            UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius) {
        return new RuleEvidenceContext(List.of(), List.of());
    }

    default RuleEvidenceContextPage readRuleEvidenceContextPage(
            UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius, int offset, int pageSize) {
        if (offset != 0 || pageSize < 1) throw new IllegalArgumentException("rule evidence context window is invalid");
        RuleEvidenceContext context = readRuleEvidenceContext(documentVersionId, anchorEvidenceIds, radius);
        return new RuleEvidenceContextPage(context.anchors(), context.surroundingEvidence(), false);
    }

    enum SourceAvailability {
        COMPLETE,
        PARTIAL
    }

    record RuleEvidencePage(
            List<RuleEvidence> evidence,
            boolean hasMore,
            int consumedIdentities,
            SourceAvailability sourceAvailability) {
        public RuleEvidencePage(List<RuleEvidence> evidence, boolean hasMore) {
            this(evidence, hasMore, evidence == null ? 0 : evidence.size(), SourceAvailability.COMPLETE);
        }

        public RuleEvidencePage(List<RuleEvidence> evidence, boolean hasMore, int consumedIdentities) {
            this(evidence, hasMore, consumedIdentities, SourceAvailability.COMPLETE);
        }

        public RuleEvidencePage {
            if (consumedIdentities < 0 || sourceAvailability == null) {
                throw new IllegalArgumentException("consumed evidence identity count and availability are required");
            }
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record RuleEvidenceContextPage(
            List<RuleEvidence> anchors, List<RuleEvidence> surroundingEvidence, boolean hasMore) {
        public RuleEvidenceContextPage {
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
            surroundingEvidence = surroundingEvidence == null ? List.of() : List.copyOf(surroundingEvidence);
        }
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
