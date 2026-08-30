package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
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
                plan, plan.sections().getFirst(), UUID.randomUUID(), false);

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
                plan, plan.sections().getFirst(), UUID.randomUUID(), false);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void keepsAFailedExactPageReadLocalWithoutFallingBackToLossySearch() {
        TeachingPlan visualPlan = new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                "Test game",
                "Keep the exact-page fallback explicit.",
                List.of(new PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Explain setup from the bound page.",
                        true,
                        true,
                        List.of("setup"),
                        List.of("setup"),
                        List.of(2))),
                "player",
                Instant.now());
        RuleEvidence placeholder = new RuleEvidence(
                UUID.randomUUID(),
                documentVersionId,
                "SETUP",
                "Setup",
                "Image-only page",
                2,
                2,
                List.of(),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of(placeholder);
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID requestedVersion, java.util.Set<Integer> pages, boolean includePageImages) {
                throw new IllegalStateException("page image storage unavailable");
            }
        };

        var result = retriever(tools).retrieve(
                visualPlan, visualPlan.sections().getFirst(), UUID.randomUUID(), true);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.EMPTY);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.canonicalPageObservation()).isEmpty();
    }

    @Test
    void doesNotClaimACompleteObservationWhenOneRequestedPageIsAbsent() {
        RuleEvidence pageTwo = evidence(UUID.randomUUID(), documentVersionId, "Only page two was returned.");

        var observation = TeachingVisualEvidenceResolver.CanonicalPageObservation.complete(
                UUID.randomUUID(), documentVersionId, java.util.Set.of(2, 5), List.of(pageTwo));

        assertThat(observation).isEmpty();
    }

    private TeachingSectionEvidenceRetriever retriever(AssistantReadTools tools) {
        ImmediateAuditedAgentInvocations invocations = new ImmediateAuditedAgentInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        return new TeachingSectionEvidenceRetriever(
                tools,
                new PolicyEvidenceVerifier(),
                invocations,
                new TeachingVisualEvidenceResolver(
                        tools,
                        invocations,
                        visualFacts,
                        VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts)));
    }

    private RuleEvidence evidence(UUID chunkId, UUID versionId, String excerpt) {
        return new RuleEvidence(chunkId, versionId, "SETUP", "Setup", excerpt, 2, 2);
    }
}
