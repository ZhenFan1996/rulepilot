package com.rulepilot.recommendation.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates that an Agent-returned title is an intact span from player-authored text. */
public final class BoardGameTitleGrounding {

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
