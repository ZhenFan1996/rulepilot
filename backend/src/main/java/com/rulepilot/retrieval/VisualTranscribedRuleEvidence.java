package com.rulepilot.retrieval;

/**
 * Shared evidence envelope for atomic rule statements transcribed from an image-only rulebook page.
 *
 * <p>The marker lets downstream retrieval and teaching distinguish rule transcription from visual presentation
 * metadata without creating a dependency between those business modules.</p>
 */
public final class VisualTranscribedRuleEvidence {

    private static final String MARKER = "Visual-transcribed rule evidence.";

    private VisualTranscribedRuleEvidence() {}

    public static String render(String factualSummary) {
        if (factualSummary == null || factualSummary.isBlank()) {
            throw new IllegalArgumentException("visual transcription factual summary is invalid");
        }
        return MARKER + " Only the statements under Visible rule facts are rule evidence. "
                + "Do not derive a per-item value from a worked total, attach a detached number to a nearby label, "
                + "or fill a missing prerequisite, action, timing, score, or exception."
                + "\nVisible rule facts: " + factualSummary.strip();
    }

    public static boolean contains(String value) {
        return value != null && value.startsWith(MARKER);
    }
}
