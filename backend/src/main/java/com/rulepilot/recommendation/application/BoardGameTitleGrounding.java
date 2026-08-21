package com.rulepilot.recommendation.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates that an Agent-returned title is an intact span from player-authored text. */
public final class BoardGameTitleGrounding {

    private static final int TITLE_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern SETTLED_TARGET_INTENT = Pattern.compile(
            "(?:已经|已|早就)?决定(?:了)?(?:要)?玩|(?:已经|已|早就)?选(?:定|好)(?:了)?(?:要玩的)?"
                    + "|\\b(?:i|we)(?:['’]ve|\\s+have)?\\s+(?:already\\s+)?decided\\s+(?:to\\s+)?play\\b"
                    + "|\\b(?:i|we)(?:['’]ve|\\s+have)?\\s+(?:already\\s+)?(?:chosen|selected)\\b",
            TITLE_PATTERN_FLAGS);
    private static final Pattern DIRECT_TARGET_CONTINUATION = Pattern.compile(
            "(?:请|麻烦|帮我|直接).{0,40}(?:找到|找出|打开).{0,40}(?:这款|这个游戏)"
                    + "|\\bfind\\b.{1,120}\\b(?:rulebook|rules|guide)\\b",
            TITLE_PATTERN_FLAGS);
    private static final Pattern PARENTHESIZED_SPAN = Pattern.compile("[（(]\\s*([^()（）]{1,120}?)\\s*[)）]");
    private static final Pattern QUOTED_SPAN = Pattern.compile("[《“\"]\\s*([^》”\"]{1,120}?)\\s*[》”\"]");
    private static final Pattern CHINESE_SETTLED_TITLE = Pattern.compile(
            "(?:(?:已经|已|早就)?决定(?:了)?(?:要)?玩|(?:已经|已|早就)?选(?:定|好)(?:了)?(?:要玩的)?)"
                    + "\\s*([^，。；！？、,;!?()（）]{1,120})",
            TITLE_PATTERN_FLAGS);
    private static final Pattern ENGLISH_SETTLED_TITLE = Pattern.compile(
            "\\b(?:decided\\s+(?:to\\s+)?play|chosen|selected)\\s+"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N} .:'’&+\\-]{0,119}?)"
                    + "(?=\\s+(?:so|and|for|tonight|then|because)\\b|[,.;!?]|$)",
            TITLE_PATTERN_FLAGS);

    private BoardGameTitleGrounding() {}

    public static boolean occursInPlayerText(String text, String title) {
        String normalizedText = normalize(text);
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isBlank()) return false;

        int fromIndex = 0;
        while (fromIndex <= normalizedText.length() - normalizedTitle.length()) {
            int index = normalizedText.indexOf(normalizedTitle, fromIndex);
            if (index < 0) return false;
            int end = index + normalizedTitle.length();
            if (hasValidLeadingBoundary(normalizedText, normalizedTitle, index)
                    && hasValidTrailingBoundary(normalizedText, normalizedTitle, end)) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    /**
     * Extracts one explicitly selected current target without asking a model to classify an unambiguous intent.
     * The candidate is always copied from the current player text and still has to resolve through the catalog.
     */
    public static Optional<String> explicitTargetTitle(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        boolean settledTarget = SETTLED_TARGET_INTENT.matcher(text).find();
        if (!settledTarget && !DIRECT_TARGET_CONTINUATION.matcher(text).find()) {
            return Optional.empty();
        }

        if (hasMultipleCapturedSpans(PARENTHESIZED_SPAN, text)) return Optional.empty();
        if (hasMultipleCapturedSpans(QUOTED_SPAN, text)) return Optional.empty();

        Optional<String> quoted = singleCapturedSpan(QUOTED_SPAN, text);
        if (quoted.isPresent()) return quoted;
        if (settledTarget) {
            Optional<String> chinese = firstCapturedSpan(CHINESE_SETTLED_TITLE, text);
            if (chinese.isPresent()) return chinese;
            Optional<String> english = firstCapturedSpan(ENGLISH_SETTLED_TITLE, text);
            if (english.isPresent()) return english;
        }
        return singleCapturedSpan(PARENTHESIZED_SPAN, text);
    }

    /**
     * Restores an explicit localized/canonical pair when the Agent returns only the leading title.
     * The returned value is always an exact player-authored span; this method never translates or
     * guesses an alias.
     */
    public static Optional<String> withImmediateParenthesizedAlias(String text, String title) {
        if (!occursInPlayerText(text, title) || text == null || title == null || title.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile(Pattern.quote(title), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text);
        while (matcher.find()) {
            if (!hasValidLeadingBoundary(text, title, matcher.start())
                    || !hasValidTrailingBoundary(text, title, matcher.end())) {
                continue;
            }
            int opening = matcher.end();
            while (opening < text.length() && Character.isWhitespace(text.charAt(opening))) opening++;
            if (opening >= text.length()) continue;
            char openingCharacter = text.charAt(opening);
            char closingCharacter = openingCharacter == '(' ? ')' : openingCharacter == '（' ? '）' : 0;
            if (closingCharacter == 0) continue;
            int closing = text.indexOf(closingCharacter, opening + 1);
            if (closing < 0 || text.substring(opening + 1, closing).isBlank()) continue;
            String expanded = text.substring(matcher.start(), closing + 1).strip();
            if (expanded.length() <= 120) return Optional.of(expanded);
        }
        return Optional.empty();
    }

    private static Optional<String> singleCapturedSpan(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        String candidate = matcher.group(1).strip();
        if (candidate.isBlank() || matcher.find()) return Optional.empty();
        return Optional.of(candidate);
    }

    private static boolean hasMultipleCapturedSpans(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && matcher.find();
    }

    private static Optional<String> firstCapturedSpan(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        String candidate = matcher.group(1).strip();
        return candidate.isBlank() ? Optional.empty() : Optional.of(candidate);
    }

    private static boolean hasValidLeadingBoundary(String text, String title, int index) {
        if (index == 0 || !hasLatinOrDigitEdge(title, true)) return true;
        int previous = text.codePointBefore(index);
        return !isLatinOrDigit(previous);
    }

    private static boolean hasValidTrailingBoundary(String text, String title, int end) {
        if (end == text.length() || !hasLatinOrDigitEdge(title, false)) return true;
        int next = text.codePointAt(end);
        return !isLatinOrDigit(next);
    }

    private static boolean hasLatinOrDigitEdge(String value, boolean leading) {
        int codePoint = leading ? value.codePointAt(0) : value.codePointBefore(value.length());
        return isLatinOrDigit(codePoint);
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return Character.isDigit(codePoint) || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }
}
