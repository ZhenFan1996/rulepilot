package com.rulepilot.assistant.application;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure bounded selection of already-retrieved evidence for an answer-model request. */
final class AnswerEvidenceSelectionPolicy {

    private AnswerEvidenceSelectionPolicy() {}

    static List<HybridEvidenceHit> select(
            String normalizedQuestion,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds) {
        Map<UUID, HybridEvidenceHit> selected = new LinkedHashMap<>();
        List<HybridEvidenceHit> selectedVisualEvidence = visualEvidenceIds.stream()
                .map(evidenceById::get)
                .filter(java.util.Objects::nonNull)
                .sorted(byScoreThenId())
                .toList();
        List<HybridEvidenceHit> selectedIntentAnchors = intentAnchors.stream()
                .map(hit -> evidenceById.get(hit.evidence().chunkId()))
                .filter(java.util.Objects::nonNull)
                .filter(hit -> visualEvidenceIds.isEmpty() || !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                .toList();
        if (AnswerEvidencePolicy.visualEvidencePriority(normalizedQuestion)) {
            addAll(selected, selectedVisualEvidence);
            addAll(selected, selectedIntentAnchors);
        } else {
            addAll(selected, selectedIntentAnchors);
            addAll(selected, selectedVisualEvidence);
        }
        if (selected.size() < 3) {
            evidenceById.values().stream()
                    .filter(hit -> visualEvidenceIds.isEmpty() || !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                    .sorted(byScoreThenId())
                    .forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        List<HybridEvidenceHit> selectedEvidence = selected.values().stream().limit(5).toList();
        return AnswerEvidencePolicy.isEndgameResolutionQuestion(normalizedQuestion)
                ? withComplementaryEndgameEvidence(normalizedQuestion, selectedEvidence)
                : selectedEvidence;
    }

    static boolean hasEvidencedEndgameTiming(HybridEvidenceHit hit) {
        if (hit == null) return false;
        String text = (hit.evidence().heading() + "\n" + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        return text.contains("when the round ends")
                || text.contains("end of a round")
                || text.contains("end of the round")
                || text.contains("ending the round")
                || text.contains("轮末")
                || text.contains("回合结束");
    }

    private static List<HybridEvidenceHit> withComplementaryEndgameEvidence(
            String normalizedQuestion, List<HybridEvidenceHit> selectedEvidence) {
        List<HybridEvidenceHit> decisiveEvidence = selectedEvidence.stream()
                .filter(AnswerEvidencePolicy::hasEndgameResolution)
                .sorted(Comparator.comparingInt(AnswerEvidencePolicy::endgameResolutionDetailScore).reversed())
                .limit(1)
                .toList();
        if (decisiveEvidence.isEmpty()) return selectedEvidence;
        LinkedHashMap<UUID, HybridEvidenceHit> complementary = new LinkedHashMap<>();
        HybridEvidenceHit decisive = decisiveEvidence.getFirst();
        complementary.put(decisive.evidence().chunkId(), decisive);
        String question = normalizedQuestion.toLowerCase(Locale.ROOT);
        if (AnswerEvidencePolicy.asksScoring(question)) {
            selectedEvidence.stream()
                    .filter(hit -> AnswerEvidencePolicy.hasEndgameScoring(hit.evidence().excerpt()))
                    .findFirst()
                    .ifPresent(hit -> complementary.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        if (AnswerEvidencePolicy.asksTie(question)) {
            selectedEvidence.stream()
                    .filter(hit -> AnswerEvidencePolicy.hasEndgameTie(hit.evidence().excerpt()))
                    .findFirst()
                    .ifPresent(hit -> complementary.putIfAbsent(hit.evidence().chunkId(), hit));
        }
        selectedEvidence.stream()
                .filter(AnswerEvidenceSelectionPolicy::hasEvidencedEndgameTiming)
                .filter(hit -> !decisive.evidence().chunkId().equals(hit.evidence().chunkId()))
                .findFirst()
                .ifPresent(hit -> complementary.putIfAbsent(hit.evidence().chunkId(), hit));
        return complementary.values().stream().toList();
    }

    private static Comparator<HybridEvidenceHit> byScoreThenId() {
        return Comparator.comparingDouble(HybridEvidenceHit::score)
                .reversed()
                .thenComparing(hit -> hit.evidence().chunkId());
    }

    private static void addAll(Map<UUID, HybridEvidenceHit> selected, List<HybridEvidenceHit> hits) {
        hits.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
    }
}
