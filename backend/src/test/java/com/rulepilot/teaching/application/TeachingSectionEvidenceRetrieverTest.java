package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeachingSectionEvidenceRetrieverTest {

    private final UUID documentVersionId = UUID.randomUUID();
    private final TeachingPlan plan = new TeachingPlan(
            UUID.randomUUID(),
            documentVersionId,
            "Test game",
            "A focused retrieval fixture.",
            List.of(new PlannedSection(
                    1,
                    "setup",
                    "Setup",
                    "Explain setup.",
                    true,
                    false,
                    List.of("setup", "starting pieces"),
                    List.of("setup"))),
            "player",
            Instant.now());

    @Test
    void rejectsConflictingSnapshotsBeforeTheyReachComposition() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence first = evidence(chunkId, documentVersionId, "Place the board in the center.");
        RuleEvidence conflicting = new RuleEvidence(
                chunkId, documentVersionId, "SETUP", "Different heading", "Place it elsewhere.", 2, 2);
        AtomicInteger calls = new AtomicInteger();
        TeachingSectionEvidenceRetriever retriever = retriever(request ->
                calls.getAndIncrement() == 0 ? List.of(first) : List.of(conflicting));

        TeachingSectionEvidenceRetriever.Result result = retriever.retrieve(
                plan, plan.sections().getFirst(), UUID.randomUUID(), 3, false);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.EMPTY);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void rejectsAnotherDocumentVersionsEvidenceAfterVersionScopedSearch() {
        RuleEvidence wrongVersion = evidence(UUID.randomUUID(), UUID.randomUUID(), "Place the board in the center.");
        TeachingSectionEvidenceRetriever retriever = retriever(request -> {
            assertThat(request.documentVersionId()).isEqualTo(documentVersionId);
            assertThat(request.includeAdjacentContext()).isTrue();
            assertThat(request.includePageImages()).isFalse();
            return List.of(wrongVersion);
        });

        TeachingSectionEvidenceRetriever.Result result = retriever.retrieve(
                plan, plan.sections().getFirst(), UUID.randomUUID(), 3, false);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(3);
    }

    private TeachingSectionEvidenceRetriever retriever(AssistantReadTools tools) {
        ImmediateAuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        return new TeachingSectionEvidenceRetriever(
                tools,
                new PolicyEvidenceVerifier(),
                invocations,
                new TeachingVisualEvidenceResolver(
                        tools,
                        invocations,
                        VisualRulebookPageFacts.empty(),
                        VisualRulebookPageCatalogModel.unavailable()));
    }

    private RuleEvidence evidence(UUID chunkId, UUID versionId, String excerpt) {
        return new RuleEvidence(chunkId, versionId, "SETUP", "Setup", excerpt, 2, 2);
    }
}
