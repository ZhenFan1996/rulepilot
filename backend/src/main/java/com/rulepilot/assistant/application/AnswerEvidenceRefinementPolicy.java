package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, game-independent policy for when deterministic evidence has an explicit coverage gap. */
final class AnswerEvidenceRefinementPolicy {

    private static final Pattern COMPOUND_SEPARATOR = Pattern.compile(
            "[?？!！;；]+|[,，、]+|\\s+(?i:and|or|then|also)\\s+|(?:以及|并且|然后|同时|还是)");
    private static final Pattern DISTINCTIVE_TERM = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Pattern VISUAL_REFERENCE = Pattern.compile(
            "(?iu)(?:\\b(?:icon|symbol|diagram|table|arrow|image|picture|board layout|map layout)\\b"
                    + "|图标|符号|图示|示意图|表格|箭头|图片|版图|布局)");
    private static final Pattern RULE_RELATIONSHIP = Pattern.compile(
            "(?iu)\\b(?:except(?:ion)?|unless|instead|override|precedence|special rule|general rule|takes priority|"
                    + "contradict|which rule applies)\\b|"
                    + "\\b(?:rules?|effects?|abilities?)\\b.{0,80}\\bconflicts?\\b|"
                    + "\\bconflicts?\\b.{0,80}\\b(?:rules?|effects?|abilities?)\\b|"
                    + "例外|除非|改为|取代|覆盖|优先|特殊规则|一般规则|通用规则|"
                    + "(?:规则|效果|能力).{0,40}(?:冲突|矛盾)|(?:冲突|矛盾).{0,40}(?:规则|效果|能力)|"
                    + "以哪个为准|哪条.*适用");
    private static final Set<String> QUESTION_STOP_TERMS = Set.of(
            "about", "after", "before", "does", "from", "game", "have", "into", "many", "much",
            "other", "play", "player", "players", "rule", "rules", "that", "the", "then", "this",
            "what", "when", "where", "which", "with", "would");

    private AnswerEvidenceRefinementPolicy() {}

    static boolean requiresRefinement(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerEvidenceRetriever.Result deterministic) {
        return requiresRefinement(question, context, AnswerQuestionPlan.fallback(question), deterministic);
    }

    static boolean requiresRefinement(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerQuestionPlan plan,
            AnswerEvidenceRetriever.Result deterministic) {
        if (question == null || context == null || deterministic == null
                || deterministic.state() != AnswerEvidenceRetriever.State.READY) {
            return false;
        }
        if (deterministic.evidence().isEmpty()) return true;
        if (plan != null && plan.agentPlanned()
                && (plan.subquestions().size() > 1
                        || plan.evidenceNeeds().stream()
                                .anyMatch(need -> need != com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.DIRECT_RULE))) {
            return true;
        }
        String playerQuestion = question.normalizedQuestion();
        if (AnswerEvidencePolicy.asksForCompleteList(playerQuestion)) return true;
        if (asksAboutVisualReference(playerQuestion)) return true;
        if (asksAboutRuleRelationship(playerQuestion)) return true;
        if (context.previousQuestion() != null && !context.previousQuestion().isBlank()) return true;
        if (hasMultipleObligations(playerQuestion)) return true;
        return lacksDirectLexicalAnchor(playerQuestion, deterministic.evidence());
    }

    static boolean hasMultipleObligations(String playerQuestion) {
        if (playerQuestion == null || playerQuestion.isBlank()) return false;
        return COMPOUND_SEPARATOR.splitAsStream(playerQuestion)
                        .map(String::strip)
                        .filter(part -> part.length() >= 2)
                        .distinct()
                        .limit(3)
                        .count()
                > 1;
    }

    static boolean asksAboutVisualReference(String playerQuestion) {
        return playerQuestion != null && VISUAL_REFERENCE.matcher(playerQuestion).find();
    }

    static boolean asksAboutRuleRelationship(String playerQuestion) {
        return playerQuestion != null && RULE_RELATIONSHIP.matcher(playerQuestion).find();
    }

    /**
     * Any same-version chunk is safe to inspect, but it is not necessarily relevant enough to answer. A single
     * question gets native refinement when no selected passage repeats a small bounded core of its distinctive
     * wording. This is only a call-routing signal; it never accepts evidence or asserts a rule relationship.
     */
    static boolean lacksDirectLexicalAnchor(String playerQuestion, List<HybridEvidenceHit> evidence) {
        Set<String> terms = distinctiveTerms(playerQuestion);
        if (terms.isEmpty()) return false;
        int requiredMatches = terms.size() >= 3 ? 2 : 1;
        return evidence.stream().noneMatch(hit -> matchingTerms(hit, terms) >= requiredMatches);
    }

    private static Set<String> distinctiveTerms(String question) {
        if (question == null || question.isBlank()) return Set.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = DISTINCTIVE_TERM.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < 16) {
            String term = matcher.group();
            if (!QUESTION_STOP_TERMS.contains(term)) terms.add(term);
        }
        return Set.copyOf(terms);
    }

    private static int matchingTerms(HybridEvidenceHit hit, Set<String> terms) {
        String source = (hit.evidence().heading() + " " + hit.evidence().excerpt()).toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(source::contains).count();
    }
}
