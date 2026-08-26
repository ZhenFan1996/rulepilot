package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Verifies that a launched preparation still has a reusable, player-readable Teaching result. */
@Component
@Profile("!test")
final class VisualRulebookTeachingEvidenceFreshness implements RulebookTeachingEvidenceFreshness {

    private final DocumentProcessing documents;
    private final DocumentVersionScopeLookup documentScopes;
    private final VisualRulebookPageFacts visualFacts;
    private final AssistantRuns runs;
    private final TeachingPlanRepository plans;
    private final IllustratedLessonRepository lessons;

    VisualRulebookTeachingEvidenceFreshness(
            DocumentProcessing documents,
            DocumentVersionScopeLookup documentScopes,
            VisualRulebookPageFacts visualFacts,
            AssistantRuns runs,
            TeachingPlanRepository plans,
            IllustratedLessonRepository lessons) {
        this.documents = documents;
        this.documentScopes = documentScopes;
        this.visualFacts = visualFacts;
        this.runs = runs;
        this.plans = plans;
        this.lessons = lessons;
    }

    @Override
    public ReuseAssessment assess(UUID documentVersionId, UUID preparationRunId, String ownerUsername) {
        if (documentVersionId == null || preparationRunId == null
                || ownerUsername == null || ownerUsername.isBlank()) {
            return ReuseAssessment.REFRESH_REQUIRED;
        }
        String owner = ownerUsername.strip();
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> owner.equals(found.createdBy()))
                .filter(found -> "READY".equals(found.processingStatus()));
        if (scope.isEmpty()) return ReuseAssessment.REFRESH_REQUIRED;
        var preparation = runs.findOwned(preparationRunId, owner)
                .map(AssistantRuns.RunDetails::run);
        if (preparation.isEmpty()) return ReuseAssessment.REFRESH_REQUIRED;
        if (!preparation.orElseThrow().state().terminal()) {
            return ReuseAssessment.IN_PROGRESS;
        }
        if (preparation.orElseThrow().state() != AssistantRunState.COMPLETED) {
            if ("AGENT_CANCELLED".equals(preparation.orElseThrow().lastErrorCode())) {
                return ReuseAssessment.CANCELLED;
            }
            if ("TEACHING_PREPARATION_STORAGE_FAILED".equals(preparation.orElseThrow().lastErrorCode())) {
                return ReuseAssessment.EXTERNAL_REPAIR_REQUIRED;
            }
            return "TEACHING_PREPARATION_INVALID_PLAN".equals(preparation.orElseThrow().lastErrorCode())
                    ? ReuseAssessment.TERMINAL_FAILURE
                    : ReuseAssessment.RETRYABLE_FAILURE;
        }
        var plan = plans.findLatest(documentVersionId, owner);
        if (plan.isEmpty()) return ReuseAssessment.REFRESH_REQUIRED;
        var latestGeneration = runs.findLatestOwned(
                        com.rulepilot.assistant.AssistantRunMode.TEACHING,
                        plan.orElseThrow().id(),
                        owner)
                .map(AssistantRuns.RunDetails::run);
        if (latestGeneration
                .map(AssistantRuns.RunSnapshot::state)
                .filter(state -> !state.terminal())
                .isPresent()) {
            return ReuseAssessment.IN_PROGRESS;
        }
        if (latestGeneration
                .map(AssistantRuns.RunSnapshot::lastErrorCode)
                .filter("AGENT_CANCELLED"::equals)
                .isPresent()) {
            return ReuseAssessment.CANCELLED;
        }
        if (latestGeneration
                .map(AssistantRuns.RunSnapshot::state)
                .filter(AssistantRunState.FAILED::equals)
                .isPresent()) {
            // A cited first section remains readable, but a transient continuation, provider, or queue failure still
            // deserves the handoff's single bounded recovery so the player is not stranded with an unfinished guide.
            return ReuseAssessment.RETRYABLE_FAILURE;
        }
        if (lessons.findLatestByPlan(plan.orElseThrow().id())
                .filter(lesson -> lesson.sections().stream().anyMatch(section ->
                        (section.evidenceStatus() == EvidenceStatus.SUPPORTED
                                || section.evidenceStatus() == EvidenceStatus.CITED_DRAFT)
                                && section.steps().stream().anyMatch(step -> !step.sourcePages().isEmpty())))
                .isEmpty()) {
            return ReuseAssessment.REFRESH_REQUIRED;
        }
        var pages = documents.pages(documentVersionId);
        boolean visualOnly = !pages.isEmpty()
                && pages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        if (!visualOnly) return ReuseAssessment.REUSABLE;
        if (plan.orElseThrow().sections().stream()
                .flatMap(section -> section.coverageTags().stream())
                .anyMatch(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG::equals)) {
            // The completed plan already localized its incomplete page catalog and published useful cited content.
            // Re-entering the handoff must reuse that result instead of turning the same bounded gap into a retry loop.
            return ReuseAssessment.REUSABLE;
        }
        var requestedPages = pages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var cached = visualFacts.find(documentVersionId, requestedPages);
        return VisualRulebookCatalogPolicy.missingPages(requestedPages, cached).isEmpty()
                ? ReuseAssessment.REUSABLE
                : ReuseAssessment.REFRESH_REQUIRED;
    }
}
