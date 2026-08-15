package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Selects a bounded evidence set without re-interpreting player wording. */
public final class AnswerEvidenceSelectionPolicy {

    private AnswerEvidenceSelectionPolicy() {}

    static List<HybridEvidenceHit> select(
            String ignoredQuestion,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds) {
        return select(
                evidenceById,
                intentAnchors,
                visualEvidenceIds,
                null,
                List.of());
    }

    static List<HybridEvidenceHit> select(
            String ignoredQuestion,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds,
            List<List<HybridEvidenceHit>> confirmedPageGroups) {
        return select(
                evidenceById,
                intentAnchors,
                visualEvidenceIds,
                null,
                confirmedPageGroups);
    }

    public static List<HybridEvidenceHit> select(
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds,
            AnswerRetrievalPlan plan,
            List<List<HybridEvidenceHit>> confirmedPageGroups) {
        Map<UUID, HybridEvidenceHit> selected = new LinkedHashMap<>();
        List<HybridEvidenceHit> visual = visualEvidenceIds.stream()
                .map(evidenceById::get)
                .filter(java.util.Objects::nonNull)
                .filter(AnswerEvidenceSelectionPolicy::eligible)
                .sorted(byScoreThenId())
                .toList();
        // A current, complete page-fact ledger is a direct rule source, not presentation metadata.
        addAll(selected, visual);
        for (List<HybridEvidenceHit> group : confirmedPageGroups) {
            group.stream()
                    .map(hit -> evidenceById.get(hit.evidence().chunkId()))
                    .filter(java.util.Objects::nonNull)
                    .filter(AnswerEvidenceSelectionPolicy::eligible)
                    .forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        intentAnchors.stream()
                .map(hit -> evidenceById.get(hit.evidence().chunkId()))
                .filter(java.util.Objects::nonNull)
                .filter(AnswerEvidenceSelectionPolicy::eligible)
                .forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));

        boolean expandedCoverage = plan != null && plan.expandedCoverageRequired();
        int targetSize = expandedCoverage ? 8 : 5;
        evidenceById.values().stream()
                .sorted(byScoreThenId())
                .filter(AnswerEvidenceSelectionPolicy::eligible)
                .filter(hit -> !selected.containsKey(hit.evidence().chunkId()))
                .limit(Math.max(0, targetSize - selected.size()))
                .forEach(hit -> selected.put(hit.evidence().chunkId(), hit));
        return selected.values().stream().limit(targetSize).toList();
    }

    private static Comparator<HybridEvidenceHit> byScoreThenId() {
        return Comparator.comparingDouble(HybridEvidenceHit::score)
                .reversed()
                .thenComparing(hit -> hit.evidence().chunkId());
    }

    private static void addAll(Map<UUID, HybridEvidenceHit> selected, List<HybridEvidenceHit> hits) {
        hits.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
    }

    private static boolean eligible(HybridEvidenceHit hit) {
        return !AnswerEvidencePolicy.isVisualPlaceholder(hit);
    }
}
