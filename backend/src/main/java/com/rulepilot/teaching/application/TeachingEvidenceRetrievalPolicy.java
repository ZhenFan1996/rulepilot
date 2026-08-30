package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure query and evidence-selection rules for one teaching section.
 *
 * <p>The policy never reads a rulebook or validates a claim. It makes the source-owned request plan and preserves
 * coverage across the resulting immutable retrieval lists.</p>
 */
final class TeachingEvidenceRetrievalPolicy {

    private TeachingEvidenceRetrievalPolicy() {}

    static String focusedQuery(String query) {
        return query.strip();
    }

    static List<String> queries(TeachingPlan.PlannedSection topic) {
        if (!topic.sourcePageNumbers().isEmpty()) return List.of();
        return topic.retrievalQueries().stream()
                .map(String::strip)
                .filter(query -> !query.isBlank())
                .distinct()
                .toList();
    }

    static List<RuleEvidence> balancedEvidence(List<List<RuleEvidence>> evidenceByIntent) {
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        for (int rank = 0; ; rank++) {
            boolean candidateAtRank = false;
            for (List<RuleEvidence> intentEvidence : evidenceByIntent) {
                if (rank >= intentEvidence.size()) {
                    continue;
                }
                candidateAtRank = true;
                RuleEvidence candidate = intentEvidence.get(rank);
                merged.putIfAbsent(candidate.chunkId(), candidate);
            }
            if (!candidateAtRank) {
                break;
            }
        }
        return List.copyOf(merged.values());
    }
}
