package com.rulepilot.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AssistantReadTools {

    List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request);

    record SearchRuleEvidence(
            UUID documentVersionId,
            String query,
            int limit,
            Set<String> sectionTypes,
            String currentSectionType,
            boolean includeAdjacentContext) {

        public SearchRuleEvidence(
                UUID documentVersionId,
                String query,
                int limit,
                Set<String> sectionTypes,
                String currentSectionType) {
            this(documentVersionId, query, limit, sectionTypes, currentSectionType, false);
        }
    }

    record RuleEvidence(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}
}
