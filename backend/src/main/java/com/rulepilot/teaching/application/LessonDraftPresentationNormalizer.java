package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;

/**
 * Presentation boundary for untrusted lesson output.
 *
 * <p>The application no longer rewrites model prose. A malformed draft is returned unchanged so schema validation can
 * request one model repair. Optional semantic evaluation is separate from synchronous publication.</p>
 */
final class LessonDraftPresentationNormalizer {
    SectionDraft normalize(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        return draft;
    }
}
