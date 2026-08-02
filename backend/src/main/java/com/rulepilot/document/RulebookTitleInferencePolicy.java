package com.rulepilot.document;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/** Chooses document-derived titles only when they look like player intent rather than file export metadata. */
public final class RulebookTitleInferencePolicy {

    private static final Set<String> DOCUMENT_WORDS = Set.of(
            "rulebook", "rules", "rule", "manual", "handbook", "guide", "regeln", "regles");
    private static final Set<String> EXPORT_WORDS = Set.of(
            "web", "print", "printer", "final", "draft", "download", "official", "full", "compressed",
            "en", "eng", "english", "de", "deu", "german", "fr", "fra", "french", "es", "spa",
            "zh", "zho", "chinese", "cn", "us", "uk");

    private RulebookTitleInferencePolicy() {}

    public static String selectPlayerTitle(
            String uploadedTitle, String inferredTitle, List<String> activeDocumentText) {
        if (uploadedTitle == null || uploadedTitle.isBlank()) return inferredTitle;
        String uploaded = uploadedTitle.strip();
        if (!looksLikeFilenameArtifact(uploaded)) return uploaded;
        if (appearsVerbatimInDocument(inferredTitle, activeDocumentText)) return inferredTitle.strip();
        String cleaned = cleanedFilenameTitle(uploaded);
        return cleaned.isBlank() ? uploaded : cleaned;
    }

    public static boolean shouldReplaceUploadedTitle(String uploadedTitle, String inferredTitle) {
        return inferredTitle != null
                && !inferredTitle.isBlank()
                && looksLikeFilenameArtifact(uploadedTitle)
                && !canonical(uploadedTitle).equals(canonical(inferredTitle));
    }

    static boolean looksLikeFilenameArtifact(String value) {
        if (value == null || value.isBlank()) return false;
        String[] tokens = canonical(value).split(" ");
        boolean documentWord = Arrays.stream(tokens).anyMatch(DOCUMENT_WORDS::contains);
        long exportMarkers = Arrays.stream(tokens)
                .filter(token -> EXPORT_WORDS.contains(token)
                        || token.matches("v?\\d+(?:\\.\\d+)*")
                        || token.matches("rev\\d+"))
                .count();
        return documentWord && exportMarkers >= 1 || exportMarkers >= 2;
    }

    private static boolean appearsVerbatimInDocument(String inferredTitle, List<String> activeDocumentText) {
        if (inferredTitle == null || inferredTitle.isBlank() || activeDocumentText == null) return false;
        String expected = canonical(inferredTitle);
        if (expected.length() < 2) return false;
        return activeDocumentText.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(RulebookTitleInferencePolicy::canonical)
                .anyMatch(text -> text.equals(expected) || (" " + text + " ").contains(" " + expected + " "));
    }

    private static String cleanedFilenameTitle(String value) {
        return Arrays.stream(canonical(value).split(" "))
                .filter(token -> !DOCUMENT_WORDS.contains(token))
                .filter(token -> !EXPORT_WORDS.contains(token))
                .filter(token -> !token.matches("v?\\d+(?:\\.\\d+)*"))
                .filter(token -> !token.matches("rev\\d+"))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
