package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;

/**
 * Publishes the exact section that already crossed the deterministic Teaching boundary.
 *
 * <p>The candidate can only be produced after schema, citation scope, document version, and optional visual geometry
 * checks pass. A section containing a quantitative or legality-changing relationship remains a cited draft until the
 * bounded whole-lesson semantic review runs. Publication never rewrites model prose, citations, or structure.</p>
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
        EvidenceStatus publicationStatus = TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(
                        candidate.planned(), candidate.draft())
                ? EvidenceStatus.CITED_DRAFT
                : EvidenceStatus.SUPPORTED;
        return new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                publicationStatus,
                section.visualKind(),
                section.visualCaption(),
                section.visualSourcePages(),
                section.visualSourceChunkIds(),
                section.steps());
    }
}
