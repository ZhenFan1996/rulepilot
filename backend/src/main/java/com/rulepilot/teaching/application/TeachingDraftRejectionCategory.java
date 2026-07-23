package com.rulepilot.teaching.application;

/**
 * Converts untrusted draft-validation failures into stable audit categories.
 *
 * <p>The validator remains authoritative. This classifier only preserves a compact, player-safe operational
 * description for retries and activity history.</p>
 */
final class TeachingDraftRejectionCategory {

    private TeachingDraftRejectionCategory() {}

    static String from(IllegalArgumentException rejection) {
        String message = rejection.getMessage() == null ? "" : rejection.getMessage();
        if (message.startsWith("Evidence validation failed:")) {
            return "EVIDENCE_POLICY_" + message.substring(message.indexOf(':') + 1)
                    .replaceAll("[^A-Z0-9_, -]", "")
                    .strip()
                    .replaceAll("[, -]+", "+");
        }
        if (message.contains("unknown evidence reference")) return "UNKNOWN_EVIDENCE_REFERENCE";
        if (message.contains("visual cites evidence outside")) return "VISUAL_CITATION_OUTSIDE_SCOPE";
        if (message.contains("step cites evidence outside")) return "STEP_CITATION_OUTSIDE_SCOPE";
        if (message.contains("visual caption has no evidence")) return "VISUAL_CITATION_MISSING";
        if (message.contains("unresolved PDF icon")) return "UNRESOLVED_PDF_MARKER";
        if (message.contains("emoji icons")) return "UNRESOLVED_EMOJI_ICON";
        if (message.contains("do not end a rule")) return "STEP_TRUNCATED";
        if (message.contains("unanswered either/or alternative")) return "STEP_UNRESOLVED_ALTERNATIVE";
        if (message.contains("internal evidence or retrieval language")) return "INTERNAL_EVIDENCE_LANGUAGE";
        if (message.contains("internal short evidence references")) return "INTERNAL_EVIDENCE_REFERENCE";
        if (message.contains("source gap, pending rule")) return "PLAYER_FACING_SOURCE_GAP";
        if (message.contains("end condition occurs at the end of a round")) return "END_OF_ROUND_TIMING_LOST";
        if (message.contains("cited end-game check")) return "ENDGAME_CHECK_DEFERRED";
        if (message.contains("VISUAL") && message.contains("attached rulebook page")) return "VISUAL_PAGE_REQUIRED";
        if (message.contains("visual focus") || message.contains("focus region")) return "VISUAL_FOCUS_INVALID";
        if (message.contains("draft must contain")) return "STEP_COUNT_INVALID";
        if (message.contains("Every step needs")) return "STEP_METADATA_INVALID";
        if (message.contains("teaching step is invalid")) return "STEP_CONTENT_INVALID";
        if (message.contains("visual caption is missing")) return "VISUAL_CAPTION_MISSING";
        if (message.contains("visual caption is longer")) return "VISUAL_CAPTION_TOO_LONG";
        if (message.contains("visual caption")) return "VISUAL_CAPTION_INVALID";
        if (message.contains("title")) return "TITLE_INVALID";
        if (message.contains("visualKind")) return "VISUAL_KIND_MISSING";
        if (message.contains("draft is missing")) return "DRAFT_MISSING";
        return "SCHEMA_OR_POLICY_INVALID";
    }
}
