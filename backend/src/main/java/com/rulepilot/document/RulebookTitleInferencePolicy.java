package com.rulepilot.document;

import java.util.List;

/** Keeps the title supplied with a document as its player-facing identity. */
public final class RulebookTitleInferencePolicy {

    private RulebookTitleInferencePolicy() {}

    public static String selectPlayerTitle(
            String uploadedTitle, String inferredTitle, List<String> activeDocumentText) {
        if (uploadedTitle != null && !uploadedTitle.isBlank()) return uploadedTitle.strip();
        return inferredTitle == null ? null : inferredTitle.strip();
    }

    public static boolean shouldReplaceUploadedTitle(String uploadedTitle, String inferredTitle) {
        return (uploadedTitle == null || uploadedTitle.isBlank())
                && inferredTitle != null
                && !inferredTitle.isBlank();
    }
}
