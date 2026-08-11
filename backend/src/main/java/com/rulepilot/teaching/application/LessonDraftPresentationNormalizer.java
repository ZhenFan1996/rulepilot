package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import java.util.regex.Pattern;

/**
 * Presentation boundary for untrusted lesson output.
 *
 * <p>The application no longer rewrites model prose. A malformed draft is returned unchanged so schema validation can
 * request one model repair; semantic wording is reviewed by the Critic.</p>
 */
final class LessonDraftPresentationNormalizer {

    private static final Pattern UNRESOLVED_PDF_MARKER = Pattern.compile("\\[([A-Za-z][A-Za-z _-]{0,30})]");
    private static final Pattern UNRESOLVED_EMOJI_ICON = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
    private static final Pattern INTERNAL_SHORT_EVIDENCE_REFERENCE =
            Pattern.compile("(?<![\\p{L}\\p{N}])E\\d{1,2}(?![\\p{L}\\p{N}])");

    SectionDraft normalize(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        return draft;
    }

    static boolean containsUnresolvedPdfMarker(String value) {
        return value != null && UNRESOLVED_PDF_MARKER.matcher(value).find();
    }

    static boolean containsUnresolvedEmojiIcon(String value) {
        return value != null && UNRESOLVED_EMOJI_ICON.matcher(value).find();
    }

    static boolean containsInternalShortEvidenceReference(String value) {
        return value != null && INTERNAL_SHORT_EVIDENCE_REFERENCE.matcher(value).find();
    }
}
