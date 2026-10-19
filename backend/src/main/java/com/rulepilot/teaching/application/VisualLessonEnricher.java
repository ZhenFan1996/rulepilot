package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.visualaid.VisualRegionCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Observes source-owned images before chapter composition; the writer owns paragraph/image selection. */
@Service
@Profile("!test")
public class VisualLessonEnricher {

    private final RulebookUnderstandingCatalog understanding;
    private final VisualLessonStepLocator stepLocator;
    private final Clock clock;
    private final Duration compatibilityWorkflowTimeout;
    private final Duration compositionAllowance;
    private final AgentExecutionControl execution;

    @Autowired
    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionCatalog indexedRegions,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            AgentExecutionControl execution,
            @Value("${rulepilot.visual.compatibility-workflow-timeout:PT10M}")
                    Duration compatibilityWorkflowTimeout,
            @Value("${rulepilot.models.request-timeout:PT2M}") Duration compositionAllowance) {
        this(
                understanding,
                pageImages,
                candidates,
                proposals,
                indexedRegions,
                locator,
                execution,
                compatibilityWorkflowTimeout, compositionAllowance,
                Clock.systemUTC());
    }

    VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionCatalog indexedRegions,
            VisualRegionLocator locator,
            AgentExecutionControl execution,
            Duration compatibilityWorkflowTimeout, Duration compositionAllowance,
            Clock clock) {
        if (clock == null) throw new IllegalArgumentException("visual enrichment clock is required");
        this.understanding = understanding;
        this.execution = execution;
        this.compositionAllowance = compositionAllowance;
        this.clock = clock;
        this.compatibilityWorkflowTimeout = compatibilityWorkflowTimeout;
        VisualReaderCropPolicy cropPolicy = new VisualReaderCropPolicy();
        this.stepLocator = new VisualLessonStepLocator(
                        pageImages,
                        candidates,
                        proposals,
                        indexedRegions,
                        locator,
                        cropPolicy,
                        execution,
                        clock,
                        compatibilityWorkflowTimeout);
    }

    static int estimatedTeachingRunModelCalls(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) return 0;
        long estimated = plan.sections().stream()
                .filter(TeachingPlan.PlannedSection::visualEvidenceRecommended)
                .mapToLong(section -> {
                    List<Integer> candidatePages = section.visualSourcePageNumbers().isEmpty()
                            ? section.sourcePageNumbers()
                            : section.visualSourcePageNumbers();
                    long pages = candidatePages.stream().distinct().count();
                    return Math.max(1L, (pages + DocumentPageImages.MAX_PAGES_PER_READ - 1L)
                            / DocumentPageImages.MAX_PAGES_PER_READ);
                })
                .sum();
        if (estimated > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching visual workload is too large");
        }
        // This sizes the initial token/deadline envelope from the Agent's bounded visual-page windows. It is not a
        // call limit: complete Agent corrections continue while durable resources remain.
        return (int) estimated;
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return stepLocator.supportsVisualEvidence(modelConfigurationOwner);
    }

    /** Understand source-owned crops before composition; no generated paragraph is used to guess a placement. */
    public List<com.rulepilot.teaching.TeachingLessonModel.VisualInput> prepare(
            TeachingPlan plan, TeachingPlan.PlannedSection planned,
            List<com.rulepilot.assistant.AssistantReadTools.RuleEvidence> evidence, UUID runId) {
        List<VisualRegionLocator.Claim> claims = java.util.stream.IntStream.range(0, evidence.size())
                .mapToObj(index -> {
                    var source = evidence.get(index);
                    return new VisualRegionLocator.Claim(source.chunkId(), source.excerpt(),
                            java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo()).boxed().toList(),
                            index + 1);
                }).toList();
        Instant observationDeadline = clock.instant().plus(compatibilityWorkflowTimeout);
        if (runId != null && execution != null) {
            // Leave the configured provider request envelope for the essential chapter writer. Optional imagery
            // must not consume all active-work time before a grounded chapter can be composed.
            Instant latestObservation = execution.budget(runId).deadlineAt().minus(compositionAllowance);
            if (latestObservation.isBefore(observationDeadline)) observationDeadline = latestObservation;
        }
        if (!clock.instant().isBefore(observationDeadline)) return List.of();
        var result = stepLocator.locateEvidence(understanding.understanding(plan.documentVersionId()),
                plan.documentVersionId(), planned.title(), claims,
                evidence.stream().map(source -> source.heading() + " " + source.excerpt()).toList(),
                plan.createdBy(), runId, observationDeadline,
                stepLocator.beginProposalWorkflow(), List.of(), planned.visualSourcePageNumbers());
        return result.regions().stream()
                .filter(region -> !region.claimContradicted() && !region.visibleDescription().isBlank())
                .map(region -> {
                    var focus = new VisualFocus(region.pageNumber(), planned.title(), region.visibleDescription(),
                            region.x(), region.y(), region.width(), region.height(), region.sourceKind());
                    return new com.rulepilot.teaching.TeachingLessonModel.VisualInput(focus.imageId(plan.documentVersionId()), focus, region.supportedEvidenceIds());
                }).distinct().toList();
    }

    public enum Outcome {
        ADDED,
        ADDED_WITH_CLAIM_CONFLICT,
        ALREADY_PRESENT,
        NO_CITED_CANDIDATE,
        NO_PAGE_IMAGE,
        LOCATOR_RETURNED_NONE,
        MODEL_SEMANTIC_REJECTED,
        MODEL_UNAVAILABLE,
        MODEL_EXPLICIT_NO_REGION,
        MODEL_MALFORMED_RESPONSE,
        MODEL_UNSUPPORTED_SCOPE,
        MODEL_INVALID_GEOMETRY,
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_BUSY,
        MODEL_PROVIDER_FAILURE,
        CANDIDATE_PREPARATION_FAILED,
        REJECTED_TOO_SMALL,
        REJECTED_NON_VISUAL,
        REJECTED_OUTSIDE_CANDIDATE,
        REJECTED_UNKNOWN_EVIDENCE,
        REJECTED_DUPLICATE,
        REJECTED_STEP_MISMATCH,
        REJECTED_CLAIM_CONFLICT
    }


}
