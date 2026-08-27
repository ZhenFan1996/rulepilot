package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness.ReuseAssessment;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VisualRulebookTeachingEvidenceFreshnessTest {

    private final UUID documentVersionId = UUID.randomUUID();
    private final DocumentProcessing documents = mock(DocumentProcessing.class);
    private final DocumentVersionScopeLookup scopes = mock(DocumentVersionScopeLookup.class);
    private final VisualRulebookPageFacts facts = mock(VisualRulebookPageFacts.class);
    private final AssistantRuns runs = mock(AssistantRuns.class);
    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final IllustratedLessonRepository lessons = mock(IllustratedLessonRepository.class);
    private final VisualRulebookTeachingEvidenceFreshness freshness =
            new VisualRulebookTeachingEvidenceFreshness(documents, scopes, facts, runs, plans, lessons);

    @BeforeEach
    void readyOwnedDocument() {
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(
                new VersionScope(documentVersionId, null, "READY", "alice", "Example Rules")));
    }

    @Test
    void refreshesAVisualRulebookWhenAnyPageFactUsesAnOlderSchema() {
        reusableCompletedPreparation();
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0)));
        when(facts.find(documentVersionId, Set.of(1, 2))).thenReturn(List.of(
                completePageFact(1, PageFact.CURRENT_SCHEMA_VERSION),
                completePageFact(2, PageFact.CURRENT_SCHEMA_VERSION - 1)));

        assertThat(freshness.assess(documentVersionId, preparationRunId(), "alice"))
                .isEqualTo(ReuseAssessment.REFRESH_REQUIRED);
    }

    @Test
    void reusesACompleteCurrentVisualLedger() {
        reusableCompletedPreparation();
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0)));
        when(facts.find(documentVersionId, Set.of(1, 2))).thenReturn(List.of(
                completePageFact(1, PageFact.CURRENT_SCHEMA_VERSION),
                completePageFact(2, PageFact.CURRENT_SCHEMA_VERSION)));

        assertThat(freshness.assess(documentVersionId, preparationRunId(), "alice"))
                .isEqualTo(ReuseAssessment.REUSABLE);
    }

    @Test
    void reusesAReadablePlanThatAlreadyLocalizedItsUnavailableVisualPages() {
        TeachingPlan plan = reusableCompletedPreparation();
        TeachingPlan.PlannedSection section = mock(TeachingPlan.PlannedSection.class);
        when(section.coverageTags()).thenReturn(List.of(
                TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG));
        when(plan.sections()).thenReturn(List.of(section));
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0)));
        when(facts.find(documentVersionId, Set.of(1, 2))).thenReturn(List.of(
                completePageFact(1, PageFact.CURRENT_SCHEMA_VERSION)));

        assertThat(freshness.assess(documentVersionId, preparationRunId(), "alice"))
                .isEqualTo(ReuseAssessment.REUSABLE);
    }

    @Test
    void doesNotTreatATextRulebookAsVisualDerivedEvidence() {
        reusableCompletedPreparation();
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "Take one action.", 16)));

        assertThat(freshness.assess(documentVersionId, preparationRunId(), "alice"))
                .isEqualTo(ReuseAssessment.REUSABLE);
    }

    @Test
    void doesNotRestartAnActivePreparationThatIsAlreadyRefreshingEvidence() {
        UUID preparationRunId = UUID.randomUUID();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.LESSON_PLANNING)));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.IN_PROGRESS);
    }

    @Test
    void restartsACompletedPreparationWhosePlanWasNotPersisted() {
        UUID preparationRunId = preparationRunId();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.REFRESH_REQUIRED);
    }

    @Test
    void restartsACompletedPreparationWhoseFirstReadableSectionIsMissing() {
        UUID preparationRunId = preparationRunId();
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));
        when(plans.findLatest(documentVersionId, "alice")).thenReturn(Optional.of(plan));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.REFRESH_REQUIRED);
    }

    @Test
    void marksATransientPreparationFailureAsRetryable() {
        UUID preparationRunId = preparationRunId();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.FAILED)));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.RETRYABLE_FAILURE);
    }

    @Test
    void requiresExternalRepairWhenDurablePageFactsCouldNotBeStored() {
        UUID preparationRunId = preparationRunId();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.FAILED, "TEACHING_PREPARATION_STORAGE_FAILED")));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.EXTERNAL_REPAIR_REQUIRED);
    }

    @Test
    void preservesExplicitCancellationInsteadOfMakingItRetryable() {
        UUID preparationRunId = preparationRunId();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.FAILED, "AGENT_CANCELLED")));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.CANCELLED);
    }

    @Test
    void preservesCancellationOfTheDownstreamLessonGeneration() {
        UUID preparationRunId = preparationRunId();
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        UUID generationRunId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));
        when(plans.findLatest(documentVersionId, "alice")).thenReturn(Optional.of(plan));
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(
                        generationRunId,
                        AssistantRunMode.TEACHING,
                        planId,
                        AssistantRunState.FAILED,
                        "AGENT_CANCELLED")));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.CANCELLED);
    }

    @Test
    void retriesAFailedContinuationWhileKeepingItsReadableFirstSection() {
        UUID preparationRunId = preparationRunId();
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        UUID generationRunId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));
        when(plans.findLatest(documentVersionId, "alice")).thenReturn(Optional.of(plan));
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(
                        generationRunId,
                        AssistantRunMode.TEACHING,
                        planId,
                        AssistantRunState.FAILED,
                        "TEACHING_CONTINUATION_QUEUE_FULL")));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(readableLesson(planId)));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.RETRYABLE_FAILURE);
    }

    @Test
    void keepsTheHandoffUnreconciledWhileRemainingChaptersAreStillRunning() {
        UUID preparationRunId = preparationRunId();
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        UUID generationRunId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));
        when(plans.findLatest(documentVersionId, "alice")).thenReturn(Optional.of(plan));
        when(runs.findLatestOwned(AssistantRunMode.TEACHING, planId, "alice"))
                .thenReturn(Optional.of(details(
                        generationRunId,
                        AssistantRunMode.TEACHING,
                        planId,
                        AssistantRunState.RETRIEVING,
                        null)));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(readableLesson(planId)));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.IN_PROGRESS);
    }

    @Test
    void keepsADeterministicallyInvalidPlanTerminalInsteadOfBlindlyRegeneratingIt() {
        UUID preparationRunId = preparationRunId();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.FAILED, "TEACHING_PREPARATION_INVALID_PLAN")));

        assertThat(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .isEqualTo(ReuseAssessment.TERMINAL_FAILURE);
    }

    private UUID preparationRunId() {
        return UUID.nameUUIDFromBytes(("preparation:" + documentVersionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private TeachingPlan reusableCompletedPreparation() {
        UUID preparationRunId = preparationRunId();
        TeachingPlan plan = mock(TeachingPlan.class);
        UUID planId = UUID.randomUUID();
        when(plan.id()).thenReturn(planId);
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.COMPLETED)));
        when(plans.findLatest(documentVersionId, "alice")).thenReturn(Optional.of(plan));
        when(lessons.findLatestByPlan(planId)).thenReturn(Optional.of(readableLesson(planId)));
        return plan;
    }

    private IllustratedLesson readableLesson(UUID planId) {
        var step = new LessonStep(1, "Take the first action", TeachingMove.DO, "Take one action.", List.of(2), List.of());
        var section = new LessonSection(
                1, "first-turn", List.of("turn"), "First turn", true, EvidenceStatus.SUPPORTED,
                null, null, List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), planId, LessonStatus.DRAFT_READY, List.of(section), Instant.parse("2026-08-17T00:00:00Z"));
    }

    private PageFact completePageFact(int pageNumber, int schemaVersion) {
        String identifier = "RULE " + pageNumber;
        return new PageFact(
                pageNumber,
                identifier,
                identifier + ": A directly visible page rule.",
                List.of("rule"),
                List.of(),
                schemaVersion,
                List.of(),
                List.of(identifier),
                true,
                List.of(new RuleGroupFact(identifier, identifier, "A directly visible page rule.")));
    }

    private AssistantRuns.RunDetails details(UUID runId, AssistantRunState state) {
        return details(runId, state, null);
    }

    private AssistantRuns.RunDetails details(UUID runId, AssistantRunState state, String errorCode) {
        return details(
                runId,
                AssistantRunMode.TEACHING_PREPARATION,
                documentVersionId,
                state,
                errorCode);
    }

    private AssistantRuns.RunDetails details(
            UUID runId,
            AssistantRunMode mode,
            UUID subjectId,
            AssistantRunState state,
            String errorCode) {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        var run = new AssistantRuns.RunSnapshot(
                runId,
                mode,
                subjectId,
                "alice",
                state,
                3,
                now,
                now,
                state.terminal() ? now : null,
                errorCode);
        var budget = new AgentExecutionControl.BudgetSnapshot(
                40, 72, 36, 160_000, 0, 0, 0, now.plusSeconds(600), null);
        return new AssistantRuns.RunDetails(run, List.of(), budget, List.of());
    }
}
