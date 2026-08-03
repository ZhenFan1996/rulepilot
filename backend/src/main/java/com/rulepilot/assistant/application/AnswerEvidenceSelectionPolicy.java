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
import java.util.regex.Pattern;

/** Pure bounded selection of already-retrieved evidence for an answer-model request. */
final class AnswerEvidenceSelectionPolicy {

    private static final Pattern RELEVANCE_TERM = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private AnswerEvidenceSelectionPolicy() {}

    static List<HybridEvidenceHit> select(
            String normalizedQuestion,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds) {
        return select(normalizedQuestion, evidenceById, intentAnchors, visualEvidenceIds, List.of());
    }

    static List<HybridEvidenceHit> select(
            String normalizedQuestion,
            Map<UUID, HybridEvidenceHit> evidenceById,
            Collection<HybridEvidenceHit> intentAnchors,
            Set<UUID> visualEvidenceIds,
            List<List<HybridEvidenceHit>> confirmedPageGroups) {
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
                .sorted(Comparator.comparingInt((HybridEvidenceHit hit) ->
                                questionOverlap(normalizedQuestion, hit))
                        .reversed())
                .toList();
        List<List<HybridEvidenceHit>> selectedPageGroups = confirmedPageGroups.stream()
                .map(group -> group.stream()
                        .map(hit -> evidenceById.get(hit.evidence().chunkId()))
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .filter(group -> !group.isEmpty())
                .sorted(Comparator.comparingInt((List<HybridEvidenceHit> group) ->
                                questionOverlap(normalizedQuestion, group))
                        .reversed())
                .toList();
        if (AnswerEvidencePolicy.visualEvidencePriority(normalizedQuestion)) {
            addAll(selected, selectedVisualEvidence);
        }
        selectedPageGroups.forEach(group -> addAll(selected, group));
        addAll(selected, selectedIntentAnchors);
        if (!AnswerEvidencePolicy.visualEvidencePriority(normalizedQuestion)) addAll(selected, selectedVisualEvidence);
        boolean expandedCoverage = AnswerEvidencePolicy.asksForCompleteList(normalizedQuestion)
                || AnswerEvidenceRefinementPolicy.hasMultipleObligations(normalizedQuestion);
        int targetSize = expandedCoverage ? 8 : 3;
        if (selected.size() < targetSize) {
            evidenceById.values().stream()
                    .filter(hit -> visualEvidenceIds.isEmpty() || !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                    .sorted(byScoreThenId())
                    .filter(hit -> !selected.containsKey(hit.evidence().chunkId()))
                    .limit(targetSize - selected.size())
                    .forEach(hit -> selected.put(hit.evidence().chunkId(), hit));
        }
        int evidenceLimit = expandedCoverage ? 8 : 5;
        List<HybridEvidenceHit> selectedEvidence = selected.values().stream().limit(evidenceLimit).toList();
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
                    .max(Comparator.comparingInt(hit ->
                            AnswerEvidencePolicy.endgameScoringDetailScore(hit.evidence().excerpt())))
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

    private static int questionOverlap(String question, HybridEvidenceHit hit) {
        if (question == null || question.isBlank() || hit == null) return 0;
        String evidence = (hit.evidence().heading() + ' ' + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        var matcher = RELEVANCE_TERM.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < 64) terms.add(matcher.group());
        return (int) terms.stream().filter(evidence::contains).count();
    }

    private static int questionOverlap(String question, List<HybridEvidenceHit> group) {
        if (question == null || question.isBlank() || group == null || group.isEmpty()) return 0;
        String evidence = group.stream()
                .map(hit -> hit.evidence().heading() + ' ' + hit.evidence().excerpt())
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        var matcher = RELEVANCE_TERM.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < 64) terms.add(matcher.group());
        return (int) terms.stream().filter(evidence::contains).count();
    }

    private static void addAll(Map<UUID, HybridEvidenceHit> selected, List<HybridEvidenceHit> hits) {
        hits.forEach(hit -> selected.putIfAbsent(hit.evidence().chunkId(), hit));
    }
}
