package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeachingEvidenceAgentTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void keepsACompleteReadySectionOnTheDeterministicFastPath() {
        AtomicInteger nativeCalls = new AtomicInteger();
        NativeToolAgent nativeAgent = request -> {
            nativeCalls.incrementAndGet();
            throw new AssertionError("complete section evidence must not invoke the native Agent");
        };
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                nativeAgent, scopes, tools(List.of()), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(2));
        var deterministic = verified(2, evidence(UUID.randomUUID(), 2, "Complete setup rule."));

        var result = agent.refine(plan, plan.sections().getFirst(), runId, deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(nativeCalls).hasValue(0);
        verify(scopes, never()).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fillsAMissingValidatedSourcePageWithCanonicalObservedEvidence() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RuleEvidence later = evidence(UUID.randomUUID(), 5, "Deal each player the starting items.");
        java.util.concurrent.atomic.AtomicReference<NativeToolAgent.RunRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolAgent nativeAgent = request -> {
            captured.set(request);
            return completed(later.chunkId());
        };
        NativeToolScopes scopes = scopes();
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                nativeAgent, scopes, tools(List.of(later)), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(2, 5));

        var result = agent.refine(plan, plan.sections().getFirst(), runId, verified(2, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.evidence()).extracting(RuleEvidence::pageFrom).containsExactly(5, 2);
        assertThat(captured.get().allowedTools()).containsExactly("read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion())
                .containsExactly("read_rule_pages");
        assertThat(captured.get().maxToolCalls()).isEqualTo(1);
        assertThat(captured.get().playerRequest()).contains("Missing validated source pages: [5]");
    }

    @Test
    void letsAnEmptySectionRecoverButNeverUsesModelProseAsEvidence() {
        RuleEvidence recovered = evidence(UUID.randomUUID(), 7, "The round ends after this procedure.");
        NativeToolAgent nativeAgent = request -> new RunResult(
                RunStatus.COMPLETED,
                "Invented chapter prose that must be discarded.",
                "MODEL_COMPLETED",
                2,
                1,
                List.of(observation(recovered.chunkId())));
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                nativeAgent, scopes(), tools(List.of(recovered)), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(7));
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = agent.refine(plan, plan.sections().getFirst(), runId, empty);

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).singleElement().extracting(RuleEvidence::excerpt)
                .isEqualTo(recovered.excerpt());
        assertThat(result.evidence().getFirst().excerpt()).doesNotContain("Invented chapter prose");
    }

    @Test
    void keepsAnUnanchoredEmptyChapterInsufficientInsteadOfLettingTheModelProveItsOwnObjective() {
        AtomicInteger nativeCalls = new AtomicInteger();
        NativeToolAgent nativeAgent = request -> {
            nativeCalls.incrementAndGet();
            throw new AssertionError("an unanchored chapter must not ask the model to establish relevance");
        };
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                nativeAgent, scopes, tools(List.of()), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of());
        var empty = new TeachingSectionEvidenceRetriever.Result(
                List.of(), 1, TeachingSectionEvidenceRetriever.State.EMPTY);

        var result = agent.refine(plan, plan.sections().getFirst(), runId, empty);

        assertThat(result).isSameAs(empty);
        assertThat(nativeCalls).hasValue(0);
        verify(scopes, never()).create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesVerifiedEvidenceAndCountsSpentCallsWhenTheLoopFallsBack() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        NativeToolAgent nativeAgent = request -> new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "ITERATION_LIMIT",
                4,
                3,
                List.of());
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                nativeAgent, scopes(), tools(List.of()), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(2, 5));

        var result = agent.refine(plan, plan.sections().getFirst(), runId, verified(2, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).containsExactly(initial);
        assertThat(result.toolCalls()).isEqualTo(5);
    }

    @Test
    void rejectsAConflictingCanonicalSnapshotBeforeComposition() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence initial = evidence(evidenceId, 2, "Place the shared board.");
        RuleEvidence conflicting = evidence(evidenceId, 5, "Place it somewhere else.");
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                request -> completed(evidenceId), scopes(), tools(List.of(conflicting)), new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(2, 5));

        var result = agent.refine(plan, plan.sections().getFirst(), runId, verified(1, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.INVALID);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void ignoresObservedEvidenceOutsideTheMissingValidatedSourcePages() {
        RuleEvidence initial = evidence(UUID.randomUUID(), 2, "Place the shared board.");
        RuleEvidence unrelated = evidence(UUID.randomUUID(), 9, "An unrelated appendix entry.");
        TeachingEvidenceAgent agent = new TeachingEvidenceAgent(
                request -> completed(unrelated.chunkId()),
                scopes(),
                tools(List.of(unrelated)),
                new PolicyEvidenceVerifier());
        TeachingPlan plan = plan(List.of(2, 5));

        var result = agent.refine(plan, plan.sections().getFirst(), runId, verified(1, initial));

        assertThat(result.state()).isEqualTo(TeachingSectionEvidenceRetriever.State.VERIFIED);
        assertThat(result.evidence()).containsExactly(initial);
    }

    private TeachingPlan plan(List<Integer> sourcePages) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                20,
                "Test game",
                "Teach one grounded section.",
                List.of(new PlannedSection(
                        1,
                        "setup",
                        "Setup",
                        "Make the table ready and account for every validated source page.",
                        true,
                        false,
                        List.of("setup starting items"),
                        List.of("setup"),
                        sourcePages)),
                "player",
                Instant.now());
    }

    private TeachingSectionEvidenceRetriever.Result verified(int toolCalls, RuleEvidence... evidence) {
        return new TeachingSectionEvidenceRetriever.Result(
                List.of(evidence), toolCalls, TeachingSectionEvidenceRetriever.State.VERIFIED);
    }

    private NativeToolScopes scopes() {
        NativeToolScopes scopes = mock(NativeToolScopes.class);
        when(scopes.create("player", versionId, runId)).thenReturn(Optional.of(
                new ToolScope("player", versionId, runId, Instant.now().plusSeconds(30))));
        return scopes;
    }

    private AssistantReadTools tools(List<RuleEvidence> hydrated) {
        return new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of();
            }

            @Override
            public List<RuleEvidence> readRuleEvidenceIds(UUID documentVersionId, Set<UUID> evidenceIds) {
                return hydrated;
            }
        };
    }

    private RunResult completed(UUID evidenceId) {
        return new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                2,
                1,
                List.of(observation(evidenceId)));
    }

    private ObservationRecord observation(UUID evidenceId) {
        ToolObservation observation = ToolObservation.success(
                "EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", evidenceId.toString()))),
                1);
        return new ObservationRecord(1, "read_rule_pages", "schema", observation);
    }

    private RuleEvidence evidence(UUID id, int page, String excerpt) {
        return new RuleEvidence(id, versionId, "SETUP", "Setup", excerpt, page, page);
    }
}
