package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.RulebookUnderstandingRebuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Best-effort post-publication visual work. A failure never changes the base lesson. */
@Service
@Profile("!test")
public class VisualLessonEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(VisualLessonEnrichmentService.class);
    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;
    private final VisualLessonEnricher enricher;
    private final IllustratedLessonProgressPublisher publisher;
    private final RulebookUnderstandingRebuilder understandingRebuilder;

    public VisualLessonEnrichmentService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            VisualLessonEnricher enricher,
            IllustratedLessonProgressPublisher publisher,
            RulebookUnderstandingRebuilder understandingRebuilder) {
        this.plans = plans;
        this.lessons = lessons;
        this.enricher = enricher;
        this.publisher = publisher;
        this.understandingRebuilder = understandingRebuilder;
    }

    public void enrichLatest(UUID teachingPlanId) {
        try {
            var plan = plans.findById(teachingPlanId)
                    .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
            var lesson = lessons.findLatestByPlan(teachingPlanId).orElse(null);
            if (lesson == null) return;
            publisher.publish(enrich(plan.documentVersionId(), lesson));
        } catch (RuntimeException failure) {
            log.warn("Visual lesson enrichment failed for plan {}: {}", teachingPlanId, failure.getMessage());
        }
    }

    private com.rulepilot.teaching.domain.IllustratedLesson enrich(
            UUID documentVersionId, com.rulepilot.teaching.domain.IllustratedLesson lesson) {
        try {
            return enricher.enrich(documentVersionId, lesson);
        } catch (IllegalArgumentException missingUnderstanding) {
            if (!"rulebook understanding does not exist".equals(missingUnderstanding.getMessage())) {
                throw missingUnderstanding;
            }
            log.info("Rebuilding layout evidence for legacy document {} before visual enrichment", documentVersionId);
            understandingRebuilder.rebuild(documentVersionId);
            return enricher.enrich(documentVersionId, lesson);
        }
    }
}
