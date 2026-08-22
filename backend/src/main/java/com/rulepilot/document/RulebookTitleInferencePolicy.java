package com.rulepilot.document;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects a player-facing title without letting model inference invent a different game identity. */
public final class RulebookTitleInferencePolicy {

    private static final int OPENING_PAGE_LIMIT = 2;
    private static final int OPENING_TEXT_LIMIT = 8_000;
    private static final Pattern NON_TITLE_CHARACTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    private RulebookTitleInferencePolicy() {}

    public static String selectPlayerTitle(
            String uploadedTitle, String inferredTitle, List<String> activeDocumentText) {
        String uploaded = clean(uploadedTitle);
        String inferred = clean(inferredTitle);
        if (uploaded == null) return inferred;
        if (inferred == null) return uploaded;
        return isSourceConfirmedContainedIdentity(uploaded, inferred, activeDocumentText)
                ? inferred
                : uploaded;
    }

    /**
     * Applies a title that the teaching boundary has already confirmed against the active source pages.
     *
     * <p>The publication boundary does not receive rulebook text, so it must not repeat model inference. It may only
     * persist a confirmed title when the upload label is empty or contains that title as a bounded, shorter identity.
     */
    public static boolean shouldReplaceWithSourceConfirmedTitle(
            String uploadedTitle, String sourceConfirmedTitle) {
        String uploaded = clean(uploadedTitle);
        String confirmed = clean(sourceConfirmedTitle);
        if (confirmed == null) return false;
        if (uploaded == null) return true;
        return isContainedIdentity(uploaded, confirmed);
    }

    private static boolean isSourceConfirmedContainedIdentity(
            String uploadedTitle,
            String inferredTitle,
            List<String> activeDocumentText) {
        if (!isContainedIdentity(uploadedTitle, inferredTitle)) return false;
        return occursAsIdentity(openingText(activeDocumentText), normalize(inferredTitle));
    }

    private static boolean isContainedIdentity(String uploadedTitle, String inferredTitle) {
        String uploaded = normalize(uploadedTitle);
        String inferred = normalize(inferredTitle);
        if (inferred.codePointCount(0, inferred.length()) < 2 || inferred.length() >= uploaded.length()) {
            return false;
        }
        return occursAsIdentity(uploaded, inferred);
    }

    private static String openingText(List<String> pages) {
        if (pages == null || pages.isEmpty()) return "";
        StringBuilder opening = new StringBuilder();
        int pagesRead = 0;
        for (String page : pages) {
            if (page == null || page.isBlank()) continue;
            if (pagesRead++ >= OPENING_PAGE_LIMIT || opening.length() >= OPENING_TEXT_LIMIT) break;
            int remaining = OPENING_TEXT_LIMIT - opening.length();
            opening.append(' ').append(page, 0, Math.min(page.length(), remaining));
        }
        return normalize(opening.toString());
    }

    private static boolean occursAsIdentity(String text, String title) {
        int fromIndex = 0;
        while (fromIndex <= text.length() - title.length()) {
            int index = text.indexOf(title, fromIndex);
            if (index < 0) return false;
            int end = index + title.length();
            if (validLeadingBoundary(text, title, index)
                    && validTrailingBoundary(text, title, end)) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    private static boolean validLeadingBoundary(String text, String title, int index) {
        if (index == 0 || !hasLatinOrDigitEdge(title, true)) return true;
        return !isLatinOrDigit(text.codePointBefore(index));
    }

    private static boolean validTrailingBoundary(String text, String title, int end) {
        if (end == text.length() || !hasLatinOrDigitEdge(title, false)) return true;
        return !isLatinOrDigit(text.codePointAt(end));
    }

    private static boolean hasLatinOrDigitEdge(String value, boolean leading) {
        int codePoint = leading ? value.codePointAt(0) : value.codePointBefore(value.length());
        return isLatinOrDigit(codePoint);
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalize(String value) {
        return NON_TITLE_CHARACTER
                .matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .strip()
                .replaceAll("\\s+", " ");
    }
}
