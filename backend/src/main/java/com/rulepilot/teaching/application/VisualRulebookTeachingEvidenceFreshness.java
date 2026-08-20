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
            return "TEACHING_PREPARATION_INVALID_PLAN".equals(preparation.orElseThrow().lastErrorCode())
                    ? ReuseAssessment.TERMINAL_FAILURE
                    : ReuseAssessment.RETRYABLE_FAILURE;
        }
        var plan = plans.findLatest(documentVersionId, owner);
        if (plan.isEmpty() || lessons.findLatestByPlan(plan.orElseThrow().id())
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
        var requestedPages = pages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var cached = visualFacts.find(documentVersionId, requestedPages);
        return VisualRulebookCatalogPolicy.missingPages(requestedPages, cached).isEmpty()
                ? ReuseAssessment.REUSABLE
                : ReuseAssessment.REFRESH_REQUIRED;
    }
}
