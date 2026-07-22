package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.ingestion.RulebookUnderstandingRebuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AssistantRuns runs;
    private final AuditedAgentInvocations activities;

    @Autowired
    public VisualLessonEnrichmentService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            VisualLessonEnricher enricher,
            IllustratedLessonProgressPublisher publisher,
            RulebookUnderstandingRebuilder understandingRebuilder,
            AssistantRuns runs,
            AuditedAgentInvocations activities) {
        this.plans = plans;
        this.lessons = lessons;
        this.enricher = enricher;
        this.publisher = publisher;
        this.understandingRebuilder = understandingRebuilder;
        this.runs = runs;
        this.activities = activities;
    }

    /** Compatibility constructor for focused unit tests that do not need persisted progress. */
    VisualLessonEnrichmentService(
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons,
            VisualLessonEnricher enricher,
            IllustratedLessonProgressPublisher publisher,
            RulebookUnderstandingRebuilder understandingRebuilder) {
        this(plans, lessons, enricher, publisher, understandingRebuilder, null, null);
    }

    public synchronized VisualEnrichmentLaunch launch(UUID teachingPlanId, String ownerUsername) {
        if (runs == null) throw new IllegalStateException("visual enrichment runs are unavailable");
        var existing = runs.findLatestOwned(AssistantRunMode.VISUAL_ENRICHMENT, teachingPlanId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return new VisualEnrichmentLaunch(run.id(), run.state(), run.revision(), true);
        }
        RunSnapshot run = runs.start(AssistantRunMode.VISUAL_ENRICHMENT, teachingPlanId, ownerUsername);
        return new VisualEnrichmentLaunch(run.id(), run.state(), run.revision(), false);
    }

    public void enrichLatest(UUID teachingPlanId) {
        log.info("Starting visual enrichment for teaching plan {}", teachingPlanId);
        try {
            var plan = plans.findById(teachingPlanId)
                    .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
            var lesson = lessons.findLatestByPlan(teachingPlanId).orElse(null);
            if (lesson == null) return;
            publisher.publish(enrich(plan.documentVersionId(), lesson, plan.createdBy()));
        } catch (RuntimeException failure) {
            log.warn(
                    "Visual lesson enrichment failed for plan {} ({}): {}",
                    teachingPlanId,
                    failure.getClass().getSimpleName(),
                    failure.getMessage());
        }
    }

    public void enrichLatest(UUID teachingPlanId, RunSnapshot run) {
        if (runs == null || activities == null) {
            enrichLatest(teachingPlanId);
            return;
        }
        RunSnapshot current = run;
        log.info("Starting observable visual enrichment run {} for teaching plan {}", current.id(), teachingPlanId);
        try {
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.DOCUMENT_READINESS,
                    "Loading cited pages and visual candidates");
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.RETRIEVING,
                    "Looking for compact, player-useful rulebook regions");
            var plan = plans.findById(teachingPlanId)
                    .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
            var lesson = lessons.findLatestByPlan(teachingPlanId).orElse(null);
            if (lesson == null) {
                current = runs.advance(
                        current.id(), current.revision(), AssistantRunState.VERIFYING_EVIDENCE,
                        "No published lesson needs visual enrichment");
                current = runs.advance(
                        current.id(), current.revision(), AssistantRunState.MEDIA_PACKAGING,
                        "No visual change was needed");
                runs.advance(current.id(), current.revision(), AssistantRunState.COMPLETED, "Visual enrichment finished");
                return;
            }
            VisualLessonEnricher.EnrichmentResult result = enrichWithReport(
                    plan.documentVersionId(), lesson, plan.createdBy());
            UUID runId = current.id();
            result.outcomes().forEach(outcome -> activities.record(
                    runId, ActivityType.VALIDATION, "visualSection|" + outcome.sectionPosition(),
                    activityOutcome(outcome.outcome()), outcome.summary()));
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.VERIFYING_EVIDENCE,
                    "Checking that every selected crop has cited rule evidence");
            publisher.publish(result.lesson());
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.MEDIA_PACKAGING,
                    "Publishing accepted local rulebook crops");
            runs.advance(current.id(), current.revision(), AssistantRunState.COMPLETED, "Visual enrichment finished");
        } catch (RuntimeException failure) {
            log.warn(
                    "Observable visual lesson enrichment failed for plan {} ({}): {}",
                    teachingPlanId,
                    failure.getClass().getSimpleName(),
                    failure.getMessage());
            if (current != null && !current.state().terminal()) {
                try {
                    runs.fail(current.id(), current.revision(), "VISUAL_ENRICHMENT_FAILED", "Visual enrichment stopped safely");
                } catch (RuntimeException runFailure) {
                    failure.addSuppressed(runFailure);
                }
            }
        }
    }

    public void failScheduling(VisualEnrichmentLaunch launch) {
        if (runs == null || launch == null || launch.reused()) return;
        runs.fail(
                launch.assistantRunId(), launch.revision(), "VISUAL_ENRICHMENT_FAILED",
                "Visual enrichment could not be scheduled");
    }

    private com.rulepilot.teaching.domain.IllustratedLesson enrich(
            UUID documentVersionId, com.rulepilot.teaching.domain.IllustratedLesson lesson, String modelConfigurationOwner) {
        try {
            return enricher.enrich(documentVersionId, lesson, modelConfigurationOwner);
        } catch (IllegalArgumentException missingUnderstanding) {
            if (!"rulebook understanding does not exist".equals(missingUnderstanding.getMessage())) {
                throw missingUnderstanding;
            }
            log.info("Rebuilding layout evidence for legacy document {} before visual enrichment", documentVersionId);
            understandingRebuilder.rebuild(documentVersionId);
            return enricher.enrich(documentVersionId, lesson, modelConfigurationOwner);
        }
    }

    private VisualLessonEnricher.EnrichmentResult enrichWithReport(
            UUID documentVersionId, com.rulepilot.teaching.domain.IllustratedLesson lesson, String modelConfigurationOwner) {
        try {
            return enricher.enrichWithReport(documentVersionId, lesson, modelConfigurationOwner);
        } catch (IllegalArgumentException missingUnderstanding) {
            if (!"rulebook understanding does not exist".equals(missingUnderstanding.getMessage())) {
                throw missingUnderstanding;
            }
            log.info("Rebuilding layout evidence for legacy document {} before visual enrichment", documentVersionId);
            understandingRebuilder.rebuild(documentVersionId);
            return enricher.enrichWithReport(documentVersionId, lesson, modelConfigurationOwner);
        }
    }

    private ActivityOutcome activityOutcome(VisualLessonEnricher.Outcome outcome) {
        return outcome == VisualLessonEnricher.Outcome.ADDED || outcome == VisualLessonEnricher.Outcome.ALREADY_PRESENT
                ? ActivityOutcome.SUCCEEDED
                : ActivityOutcome.REJECTED;
    }

    public record VisualEnrichmentLaunch(UUID assistantRunId, AssistantRunState state, long revision, boolean reused) {}
}
