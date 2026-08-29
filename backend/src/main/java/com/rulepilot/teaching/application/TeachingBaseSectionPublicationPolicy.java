package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;

/**
 * Publishes the exact section that already crossed the deterministic Teaching boundary.
 *
 * <p>The candidate can only be produced after schema, citation scope, document version, and optional visual geometry
 * checks pass. Publication never rewrites model prose, citations, or structure. Optional semantic evaluation must
 * not become a second production publication owner.</p>
 */
final class TeachingBaseSectionPublicationPolicy {

    LessonSection publish(TeachingSectionDraftCandidate candidate) {
        if (candidate == null || candidate.section() == null) {
            throw new IllegalArgumentException("validated teaching candidate is required");
        }
        LessonSection section = candidate.section();
        if (section.evidenceStatus() == EvidenceStatus.INSUFFICIENT_EVIDENCE) {
            throw new IllegalArgumentException("an evidence-insufficient section cannot be published");
        }
        return new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                EvidenceStatus.SUPPORTED,
                section.visualKind(),
                section.visualCaption(),
                section.visualSourcePages(),
                section.visualSourceChunkIds(),
                section.steps());
    }
}
