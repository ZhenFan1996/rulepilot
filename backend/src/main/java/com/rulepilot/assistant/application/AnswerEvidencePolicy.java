package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure, evidence-only answer rules shared by retrieval selection and final citation validation.
 *
 * <p>These predicates identify what source material can support a player question. They never create a rule or
 * change player-facing model text.</p>
 */
final class AnswerEvidencePolicy {

    private static final Pattern UNRESOLVED_VISUAL_SYMBOL = Pattern.compile(
            "(?iu)\\b(icon|symbol|pictograph)\\b|图标|符号|\\p{So}");
    private static final Pattern VISUAL_EVIDENCE_PRIORITY_QUESTION = Pattern.compile(
            "(?iu)\\b(which|what|resource|token|icon|symbol|pay|cost|gain|spend|score|stake)\\b"
                    + "|支付|费用|代价|获得|得分|令牌|标记|图标|符号|胜利点|资源|下注");
    private static final Pattern PRINTED_IDENTIFIER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])[\\p{L}]{1,4}\\s*[#_-]\\s*\\d{1,4}(?![\\p{L}\\p{N}])");
    private static final Pattern EVIDENCED_ENDGAME_TRIGGER = Pattern.compile(
            "(?isu)(?=.*(?:\\bend\\s+(?:the\\s+)?game\\b|\\bgame\\s+ends?\\b|\\bend\\s+condition\\b|"
                    + "游戏结束|结束条件|终局))"
                    + "(?=.*(?:\\bif\\b|\\bwhen\\b|\\bafter\\b|若|如果|当|达到|至少)).*");
    private static final Pattern EVIDENCED_ENDGAME_SCORING = Pattern.compile(
            "(?iu)(?:score|scoring|points?|winner|wins?|计分|得分|分数|获胜|胜者)");
    private static final Pattern EVIDENCED_ENDGAME_TIE = Pattern.compile(
            "(?isu)(?:on\\s+a\\s+tie|tie.{0,100}(?:winner|wins?|break)|平局.{0,80}(?:获胜|胜者|比较|决胜)|同分.{0,80}(?:获胜|胜者|比较|决胜))");
    private static final Pattern COMPLETE_LIST_QUESTION = Pattern.compile(
            "(?iu)(?:\\b(?:all|each|every|respectively|list)\\b|\\bcomplete\\s+list\\b"
                    + "|\\b\\d+\\s+(?:different|types?|items?|features?|technologies?|steps?)\\b"
                    + "|全部|所有|每个|各个|分别|全部列出|逐一|一共有\\s*\\d+个)");
    private static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";

    private AnswerEvidencePolicy() {}

    static boolean hasUnresolvedVisualSymbol(String value) {
        return value != null && UNRESOLVED_VISUAL_SYMBOL.matcher(value).find();
    }

    static boolean visualEvidencePriority(String question) {
        return question != null
                && (VISUAL_EVIDENCE_PRIORITY_QUESTION.matcher(question).find()
                        || PRINTED_IDENTIFIER.matcher(question).find());
    }

    /**
     * Printed catalogue identifiers are document-derived coordinates, not game vocabulary. Preserving them as an
     * exact bounded query prevents a language rewrite or a broad intent expansion from dropping the strongest page
     * locator available in the player's question.
     */
    static List<String> printedIdentifiers(String question) {
        if (question == null || question.isBlank()) return List.of();
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        var matcher = PRINTED_IDENTIFIER.matcher(question);
        while (matcher.find() && identifiers.size() < 24) {
            identifiers.add(matcher.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        }
        return List.copyOf(identifiers);
    }

    /**
     * A complete-list request has a coverage obligation: a single highly ranked paragraph is not enough when the
     * player asks for every item. This invariant is independent of any rulebook vocabulary and is shared by query
     * planning and bounded evidence selection.
     */
    static boolean asksForCompleteList(String question) {
        return question != null && COMPLETE_LIST_QUESTION.matcher(question).find();
    }

    static boolean hasEndgameResolution(EvidenceInput source) {
        return source != null
                && source.excerpt() != null
                && !"COMPONENTS".equalsIgnoreCase(source.sectionType())
                && EVIDENCED_ENDGAME_TRIGGER.matcher(source.excerpt()).find();
    }

    static boolean hasEndgameResolution(HybridEvidenceHit hit) {
        if (hit == null) return false;
        return hasEndgameResolution(new EvidenceInput(
                hit.evidence().chunkId(),
                hit.evidence().sectionType(),
                hit.evidence().heading(),
                hit.evidence().excerpt(),
                hit.evidence().pageFrom(),
                hit.evidence().pageTo()));
    }

    static int endgameResolutionDetailScore(HybridEvidenceHit hit) {
        if (!hasEndgameResolution(hit)) return 0;
        String excerpt = hit.evidence().excerpt();
        int score = 10;
        if (hasEndgameScoring(excerpt)) score += 10;
        if (hasEndgameTie(excerpt)) score += 10;
        if (excerpt.toLowerCase(Locale.ROOT).contains("clean up") || excerpt.contains("清理")) score += 4;
        String heading = hit.evidence().heading().toLowerCase(Locale.ROOT);
        if (heading.contains("ending") || heading.contains("end game") || heading.contains("round")) score += 12;
        return score;
    }

    static boolean hasEndgameScoring(String excerpt) {
        return excerpt != null && EVIDENCED_ENDGAME_SCORING.matcher(excerpt).find();
    }

    static int endgameScoringDetailScore(String excerpt) {
        if (excerpt == null) return 0;
        String normalized = excerpt.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "score", "scoring", "point", "计分", "得分", "分数")) return 2;
        return hasEndgameScoring(excerpt) ? 1 : 0;
    }

    static boolean hasEndgameTie(String excerpt) {
        return excerpt != null && EVIDENCED_ENDGAME_TIE.matcher(excerpt).find();
    }

    static boolean isEndgameResolutionQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return mentionsEndTrigger(normalized)
                && (containsAny(normalized, "score", "scoring", "tie", "winner", "final", "计分", "得分", "平局", "获胜", "最终")
                        || asksEndgameCompletionProcedure(normalized));
    }

    static boolean requiresEndgameResolutionCitation(String question, Collection<EvidenceInput> evidence) {
        return isEndgameResolutionQuestion(question)
                && evidence.stream().anyMatch(source -> hasRequiredEndgameResolution(question, List.of(source)));
    }

    static boolean citesEndgameResolution(
            String question, Collection<EvidenceInput> evidence, Collection<UUID> citationIds) {
        Set<UUID> citations = Set.copyOf(citationIds);
        return hasRequiredEndgameResolution(
                question,
                evidence.stream().filter(source -> citations.contains(source.chunkId())).toList());
    }

    static boolean isEndgameTimingAndTieSummary(String question, Collection<EvidenceInput> evidence) {
        return requiresEndgameResolutionCitation(question, evidence)
                && asksEndgameTiming(question)
                && asksTie(question);
    }

    static List<UUID> requiredEndgameCitationIds(
            String question, Collection<EvidenceInput> evidence, Collection<UUID> citationIds) {
        Set<UUID> citations = Set.copyOf(citationIds);
        return requiredEndgameEvidence(
                        question,
                        evidence.stream().filter(source -> citations.contains(source.chunkId())).toList())
                .stream()
                .map(EvidenceInput::chunkId)
                .distinct()
                .toList();
    }

    static boolean mentionsEndTrigger(String value) {
        return containsAny(
                value, "game end", "game ends", "end condition", "end of round", "end", "游戏结束", "结束条件", "终局", "轮末", "结束");
    }

    static boolean asksScoring(String value) {
        return containsAny(normalize(value), "score", "scoring", "point", "计分", "得分", "分数");
    }

    static boolean asksTie(String value) {
        return containsAny(normalize(value), "tie", "tied", "平局", "同分");
    }

    static boolean asksEndgameTiming(String value) {
        return containsAny(normalize(value), "when", "immediately", "何时", "什么时候", "立刻", "立即");
    }

    /**
     * Players often ask about the consequence of an end trigger rather than scoring: whether the trigger is legal
     * and whether everyone finishes the round. That still needs the decisive end-condition paragraph and must use
     * the same focused retrieval path as an explicit scoring question.
     */
    private static boolean asksEndgameCompletionProcedure(String value) {
        return containsAny(
                value,
                "can i end",
                "may i end",
                "choose to end",
                "end now",
                "players continue",
                "other players",
                "finish the round",
                "same number of turns",
                "可以结束",
                "选择结束",
                "能结束吗",
                "是否结束",
                "其他玩家",
                "继续玩吗",
                "继续进行",
                "完成本轮",
                "相同回合数");
    }

    static boolean requiresIconLegend(Collection<PageFactMatch> facts) {
        return facts.stream().anyMatch(fact -> hasUnresolvedVisualSymbol(fact.printedTerms() + " " + fact.factualSummary()));
    }

    static boolean isVisualPlaceholder(HybridEvidenceHit hit) {
        return hit != null && VISUAL_PAGE_PLACEHOLDER.equals(hit.evidence().excerpt());
    }

    static boolean requiresCrossLanguageExpansion(String question) {
        if (question == null || question.isBlank()) return false;
        return question.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count() >= 2;
    }

    static boolean sameEvidenceSnapshot(HybridEvidenceHit first, HybridEvidenceHit second) {
        var left = first.evidence();
        var right = second.evidence();
        return left.chunkId().equals(right.chunkId())
                && left.documentVersionId().equals(right.documentVersionId())
                && left.sectionType().equals(right.sectionType())
                && left.heading().equals(right.heading())
                && left.excerpt().equals(right.excerpt())
                && left.pageFrom() == right.pageFrom()
                && left.pageTo() == right.pageTo();
    }

    private static boolean hasRequiredEndgameResolution(String question, Collection<EvidenceInput> sources) {
        if (sources.stream().noneMatch(AnswerEvidencePolicy::hasEndgameResolution)) return false;
        boolean asksScoring = asksScoring(question);
        boolean asksTie = asksTie(question);
        boolean citesScoring = sources.stream()
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(AnswerEvidencePolicy::hasEndgameScoring);
        boolean citesTie = sources.stream()
                .map(EvidenceInput::excerpt)
                .filter(java.util.Objects::nonNull)
                .anyMatch(AnswerEvidencePolicy::hasEndgameTie);
        return (!asksScoring || citesScoring) && (!asksTie || citesTie);
    }

    private static List<EvidenceInput> requiredEndgameEvidence(String question, Collection<EvidenceInput> sources) {
        boolean asksScoring = asksScoring(question);
        boolean asksTie = asksTie(question);
        LinkedHashSet<EvidenceInput> required = new LinkedHashSet<>();
        sources.stream().filter(AnswerEvidencePolicy::hasEndgameResolution).findFirst().ifPresent(required::add);
        if (asksScoring) {
            sources.stream()
                    .filter(source -> hasEndgameScoring(source.excerpt()))
                    .findFirst()
                    .ifPresent(required::add);
        }
        if (asksTie) {
            sources.stream()
                    .filter(source -> hasEndgameTie(source.excerpt()))
                    .findFirst()
                    .ifPresent(required::add);
        }
        return List.copyOf(required);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... values) {
        for (String candidate : values) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
