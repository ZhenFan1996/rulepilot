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
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
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
    private final VisualRulebookTeachingEvidenceFreshness freshness =
            new VisualRulebookTeachingEvidenceFreshness(documents, scopes, facts, runs);

    @BeforeEach
    void readyOwnedDocument() {
        when(scopes.findVersion(documentVersionId)).thenReturn(Optional.of(
                new VersionScope(documentVersionId, null, "READY", "alice", "Example Rules")));
    }

    @Test
    void refreshesAVisualRulebookWhenAnyPageFactUsesAnOlderSchema() {
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0)));
        when(facts.find(documentVersionId, Set.of(1, 2))).thenReturn(List.of(
                completePageFact(1, PageFact.CURRENT_SCHEMA_VERSION),
                completePageFact(2, PageFact.CURRENT_SCHEMA_VERSION - 1)));

        assertThat(freshness.requiresRefresh(documentVersionId, UUID.randomUUID(), "alice"))
                .isTrue();
    }

    @Test
    void reusesACompleteCurrentVisualLedger() {
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "", 0),
                new PageView(2, "", 0)));
        when(facts.find(documentVersionId, Set.of(1, 2))).thenReturn(List.of(
                completePageFact(1, PageFact.CURRENT_SCHEMA_VERSION),
                completePageFact(2, PageFact.CURRENT_SCHEMA_VERSION)));

        assertThat(freshness.requiresRefresh(documentVersionId, UUID.randomUUID(), "alice"))
                .isFalse();
    }

    @Test
    void doesNotTreatATextRulebookAsVisualDerivedEvidence() {
        when(documents.pages(documentVersionId)).thenReturn(List.of(
                new PageView(1, "Take one action.", 16)));

        assertThat(freshness.requiresRefresh(documentVersionId, UUID.randomUUID(), "alice"))
                .isFalse();
    }

    @Test
    void doesNotRestartAnActivePreparationThatIsAlreadyRefreshingEvidence() {
        UUID preparationRunId = UUID.randomUUID();
        when(runs.findOwned(preparationRunId, "alice")).thenReturn(Optional.of(details(
                preparationRunId, AssistantRunState.LESSON_PLANNING)));

        assertThat(freshness.requiresRefresh(documentVersionId, preparationRunId, "alice"))
                .isFalse();
    }

    private PageFact completePageFact(int pageNumber, int schemaVersion) {
        String identifier = "RULE " + pageNumber;
        return new PageFact(
                pageNumber,
                identifier,
                identifier + ": A directly visible page rule.",
                List.of("rule"),
                List.of(),
                List.of(),
                false,
                schemaVersion,
                List.of(),
                List.of(identifier),
                true);
    }

    private AssistantRuns.RunDetails details(UUID runId, AssistantRunState state) {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        var run = new AssistantRuns.RunSnapshot(
                runId,
                AssistantRunMode.TEACHING_PREPARATION,
                documentVersionId,
                "alice",
                state,
                3,
                now,
                now,
                null,
                null);
        var budget = new AgentExecutionControl.BudgetSnapshot(
                40, 72, 36, 160_000, 0, 0, 0, now.plusSeconds(600), null);
        return new AssistantRuns.RunDetails(run, List.of(), budget, List.of());
    }
}
