package com.rulepilot.recommendation.application;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts one player-authored title only when the current turn unambiguously says that the table has already
 * selected it. The returned text is still only a lookup candidate: the local catalog must resolve it uniquely before
 * the model-free path may publish a card.
 */
final class ExplicitTargetRequest {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern SETTLED_SELECTION = Pattern.compile(
            "(?:已经|已|早就)?决定(?:了)?(?:要)?玩|(?:已经|已|早就)?选(?:定|好)(?:了)?(?:要玩的)?"
                    + "|\\b(?:i|we)(?:['’]ve|\\s+have)?\\s+(?:already\\s+)?decided\\s+(?:to\\s+)?play\\b"
                    + "|\\b(?:i|we)(?:['’]ve|\\s+have)?\\s+(?:already\\s+)?(?:chosen|selected)\\b",
            FLAGS);
    private static final Pattern DIRECT_CONTINUATION = Pattern.compile(
            "(?:请|麻烦|帮我|直接).{0,80}(?:找到|找出|打开|阅读|读|看).{0,80}(?:这款|这个游戏|规则书|规则|讲解|答疑)"
                    + "|\\b(?:find|open|read)\\b.{1,120}\\b(?:rulebook|rules|guide|questions?)\\b",
            FLAGS);
    private static final Pattern PARENTHESIZED = Pattern.compile("[（(]\\s*([^()（）]{1,120}?)\\s*[)）]");
    private static final Pattern QUOTED = Pattern.compile("[《“\"]\\s*([^》”\"]{1,120}?)\\s*[》”\"]");
    private static final Pattern CHINESE_SETTLED_TITLE = Pattern.compile(
            "(?:(?:已经|已|早就)?决定(?:了)?(?:要)?玩|(?:已经|已|早就)?选(?:定|好)(?:了)?(?:要玩的)?)"
                    + "\\s*([^，。；！？、,;!?()（）]{1,120})",
            FLAGS);
    private static final Pattern CHINESE_PLAY_TITLE = Pattern.compile(
            "(?:第一次|初次|今晚|今天|现在|准备|打算|想要|想)?玩(?:一局|一盘)?"
                    + "\\s*([^，。；！？、,;!?()（）]{1,120})",
            FLAGS);
    private static final Pattern ENGLISH_SETTLED_TITLE = Pattern.compile(
            "\\b(?:decided\\s+(?:to\\s+)?play|chosen|selected)\\s+"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N} .:'’&+\\-]{0,119}?)"
                    + "(?=\\s+(?:so|and|for|tonight|then|because)\\b|[,.;!?]|$)",
            FLAGS);
    private static final Pattern ENGLISH_PLAY_TITLE = Pattern.compile(
            "\\b(?:play|playing)\\s+"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N} .:'’&+\\-]{0,119}?)"
                    + "(?=\\s+(?:so|and|for|tonight|then|because)\\b|[,.;!?]|$)",
            FLAGS);

    private ExplicitTargetRequest() {}

    static Optional<String> title(String playerText) {
        if (playerText == null || playerText.isBlank()) return Optional.empty();
        boolean settled = SETTLED_SELECTION.matcher(playerText).find();
        if (!settled && !DIRECT_CONTINUATION.matcher(playerText).find()) return Optional.empty();

        if (hasMultiple(QUOTED, playerText) || hasMultiple(PARENTHESIZED, playerText)) {
            return Optional.empty();
        }
        Optional<String> quoted = single(QUOTED, playerText);
        if (quoted.isPresent()) return quoted;
        if (settled) {
            Optional<String> chinese = first(CHINESE_SETTLED_TITLE, playerText);
            if (chinese.isPresent()) return chinese;
            Optional<String> english = first(ENGLISH_SETTLED_TITLE, playerText);
            if (english.isPresent()) return english;
        }
        Optional<String> chinesePlay = first(CHINESE_PLAY_TITLE, playerText);
        if (chinesePlay.isPresent()) return chinesePlay;
        Optional<String> englishPlay = first(ENGLISH_PLAY_TITLE, playerText);
        if (englishPlay.isPresent()) return englishPlay;
        return single(PARENTHESIZED, playerText);
    }

    private static Optional<String> single(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        String candidate = matcher.group(1).strip();
        if (candidate.isBlank() || matcher.find()) return Optional.empty();
        return Optional.of(candidate);
    }

    private static Optional<String> first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        String candidate = matcher.group(1).strip();
        return candidate.isBlank() ? Optional.empty() : Optional.of(candidate);
    }

    private static boolean hasMultiple(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && matcher.find();
    }
}
