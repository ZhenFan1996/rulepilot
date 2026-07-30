package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requires a conditional answer to cite the supplied excerpt that best preserves the player's own condition.
 *
 * <p>The comparison deliberately derives its vocabulary from the current question and evidence. It does not know
 * game titles, component names, or mechanic labels learned from previous corpora.</p>
 */
final class AnswerConditionEvidencePolicy {

    private static final Pattern CONDITIONAL_QUESTION = Pattern.compile(
            "(?iu)\\b(?:when|if|after|before|whenever|what\\s+happens|how\\s+do)\\b|"
                    + "如果|假如|当|一旦|之后|之前|时|后|怎么|如何");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Set<String> STOP_TERMS = Set.of(
            "what", "when", "then", "does", "happen", "happens", "players", "player", "with", "from",
            "this", "that", "after", "before", "如果", "怎么", "如何", "之后", "之前", "时候");
    private static final int MIN_DIRECT_TERM_OVERLAP = 2;

    private AnswerConditionEvidencePolicy() {}

    static boolean isConditionalQuestion(String question) {
        return question != null && CONDITIONAL_QUESTION.matcher(question).find();
    }

    static String retrievalQuery(String question) {
        if (!isConditionalQuestion(question)) return null;
        return AnswerRetrievalPlanner.bounded(question
                + " condition procedure consequence timing exception continue stop "
                + "条件 流程 结果 时机 例外 继续 停止");
    }

    static boolean needsDirectCitation(ModelRequest request, List<UUID> citationIds) {
        if (request == null
                || request.question() == null
                || !isConditionalQuestion(request.question())
                || request.evidence().size() < 2) {
            return false;
        }
        Set<String> terms = terms(request.question());
        if (terms.isEmpty()) return false;
        int bestAvailable = request.evidence().stream()
                .mapToInt(source -> overlap(source, terms))
                .max()
                .orElse(0);
        if (bestAvailable < MIN_DIRECT_TERM_OVERLAP) return false;
        Set<UUID> cited = Set.copyOf(citationIds);
        int bestCited = request.evidence().stream()
                .filter(source -> cited.contains(source.chunkId()))
                .mapToInt(source -> overlap(source, terms))
                .max()
                .orElse(0);
        return bestCited < bestAvailable;
    }

    private static int overlap(EvidenceInput source, Set<String> terms) {
        String evidence = (source.heading() + " " + source.excerpt()).toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(evidence::contains).count();
    }

    private static Set<String> terms(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            String term = words.group();
            if (!containsHan(term) && !STOP_TERMS.contains(term)) terms.add(term);
        }
        Matcher hanRuns = HAN_RUN.matcher(normalized);
        while (hanRuns.find()) {
            int[] codePoints = hanRuns.group().codePoints().toArray();
            for (int index = 0; index + 1 < codePoints.length; index++) {
                String term = new String(codePoints, index, 2);
                if (!STOP_TERMS.contains(term)) terms.add(term);
            }
        }
        return Set.copyOf(terms);
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
