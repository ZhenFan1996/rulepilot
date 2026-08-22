package com.rulepilot.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects a player-facing title without letting model inference invent a different game identity. */
public final class RulebookTitleInferencePolicy {

    private static final int OPENING_PAGE_LIMIT = 2;
    private static final int OPENING_TEXT_LIMIT = 8_000;
    private static final Pattern NON_TITLE_CHARACTER = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern TITLE_TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private RulebookTitleInferencePolicy() {}

    public static String selectPlayerTitle(
            String uploadedTitle, String inferredTitle, List<String> activeDocumentText) {
        String uploaded = clean(uploadedTitle);
        String inferred = clean(inferredTitle);
        if (uploaded == null) return inferred;
        if (inferred == null) return uploaded;
        if (isSourceConfirmedContainedIdentity(uploaded, inferred, activeDocumentText)) return inferred;
        String sharedIdentity = sourceConfirmedSharedIdentity(uploaded, inferred, activeDocumentText);
        return sharedIdentity == null ? uploaded : sharedIdentity;
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

    /**
     * Recovers a title only when three independent signals agree: the upload label, the model's source reading, and a
     * standalone line near the start of the active rulebook. This handles noisy filenames without teaching production
     * vocabulary such as language, version, or export markers.
     */
    private static String sourceConfirmedSharedIdentity(
            String uploadedTitle,
            String inferredTitle,
            List<String> activeDocumentText) {
        for (String sourceLine : openingLines(activeDocumentText)) {
            String candidate = clean(sourceLine);
            if (candidate == null || candidate.length() > 160 || !stableIdentityShape(candidate)) continue;
            if (!isContainedIdentity(uploadedTitle, candidate)
                    || !isContainedIdentity(inferredTitle, candidate)) {
                continue;
            }
            return displayedIdentity(uploadedTitle, candidate);
        }
        return null;
    }

    private static List<String> openingLines(List<String> pages) {
        if (pages == null || pages.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        int pagesRead = 0;
        int remaining = OPENING_TEXT_LIMIT;
        for (String page : pages) {
            if (page == null || page.isBlank()) continue;
            if (pagesRead++ >= OPENING_PAGE_LIMIT || remaining <= 0) break;
            String excerpt = page.substring(0, Math.min(page.length(), remaining));
            remaining -= excerpt.length();
            for (String line : excerpt.split("\\R")) {
                if (!line.isBlank()) lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private static boolean stableIdentityShape(String value) {
        var matcher = TITLE_TOKEN.matcher(value);
        int tokenCount = 0;
        boolean nonLatinLetter = false;
        while (matcher.find()) {
            tokenCount++;
            nonLatinLetter = nonLatinLetter || matcher.group().codePoints().anyMatch(codePoint ->
                    Character.isLetter(codePoint)
                            && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN);
        }
        return tokenCount >= 2 || nonLatinLetter;
    }

    private static String displayedIdentity(String container, String candidate) {
        List<String> candidateTokens = tokens(candidate).stream().map(RulebookTitleInferencePolicy::normalize).toList();
        var matcher = TITLE_TOKEN.matcher(container);
        List<TitleToken> containerTokens = new ArrayList<>();
        while (matcher.find()) {
            containerTokens.add(new TitleToken(normalize(matcher.group()), matcher.start(), matcher.end()));
        }
        for (int start = 0; start + candidateTokens.size() <= containerTokens.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < candidateTokens.size(); offset++) {
                if (!containerTokens.get(start + offset).normalized().equals(candidateTokens.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return container.substring(
                                containerTokens.get(start).start(),
                                containerTokens.get(start + candidateTokens.size() - 1).end())
                        .strip();
            }
        }
        return candidate.strip();
    }

    private static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        var matcher = TITLE_TOKEN.matcher(value);
        while (matcher.find()) tokens.add(matcher.group());
        return List.copyOf(tokens);
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

    private record TitleToken(String normalized, int start, int end) {}
}
