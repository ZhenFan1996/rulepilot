package com.rulepilot.assistant.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Application-owned, game-independent classification for evidence kinds with hard publication semantics. */
final class AnswerEvidenceNeedClassifier {

    private static final Pattern EXPLICIT_ENGLISH_ADVICE_REQUEST = Pattern.compile(
            "(?:\\b(?:any|some|give|suggest|recommend|best|better|optimal|winning)\\b.{0,60}"
                    + "\\b(?:strateg(?:y|ies)|tactics?|tips?|advice|recommendations?)\\b)"
                    + "|(?:\\bhow should\\b.{0,60}\\bplay\\b)"
                    + "|(?:\\bhow can\\b.{0,60}\\bimprove\\b)");
    private static final Pattern EXPLICIT_CHINESE_ADVICE_REQUEST = Pattern.compile(
            "(?:有没有|有无|给我|请给|想要|怎么|如何|更容易|提高胜率|最佳|最优|推荐)"
                    + ".{0,16}(?:策略|打法|建议|技巧|提示|推荐)");
    private static final Pattern EXPLICIT_ENGLISH_VICTORY_ROUTES = Pattern.compile(
            "(?:\\bhow (?:do|can) (?:i|we|you) win\\b|\\bways? to win\\b|\\bvictory conditions?\\b)");
    private static final Pattern EXPLICIT_CHINESE_VICTORY_ROUTES = Pattern.compile(
            "(?:(?:怎么|如何).{0,8}(?:赢|获胜)|(?:所有|全部|哪几种).{0,10}(?:获胜|胜利).{0,8}(?:方式|条件))");

    private AnswerEvidenceNeedClassifier() {}

    /** A bare mechanic noun such as "Strategy Card" is not a request for player guidance. */
    static boolean explicitlyRequestsAdvice(String value) {
        String normalized = normalize(value);
        return EXPLICIT_ENGLISH_ADVICE_REQUEST.matcher(normalized).find()
                || EXPLICIT_CHINESE_ADVICE_REQUEST.matcher(normalized).find();
    }

    static boolean explicitlyRequestsCompleteVictoryRoutes(String value) {
        if (explicitlyRequestsAdvice(value)) return false;
        String normalized = normalize(value);
        return EXPLICIT_ENGLISH_VICTORY_ROUTES.matcher(normalized).find()
                || EXPLICIT_CHINESE_VICTORY_ROUTES.matcher(normalized).find();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
