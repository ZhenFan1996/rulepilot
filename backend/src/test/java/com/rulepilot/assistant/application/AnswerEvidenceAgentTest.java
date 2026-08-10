package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.application.RuleAnswerRateLimiter.Permit;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AnswerEvidenceAgentTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void startsAsANonTestSpringBeanWithTheApplicationScopePort() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NativeToolAgent.class, () -> mock(NativeToolAgent.class));
            context.registerBean(RuleEvidenceLookup.class, this::emptyLookup);
            context.registerBean(NativeToolScopes.class, () -> mock(NativeToolScopes.class));
            context.registerBean(RuleAnswerRateLimiter.class, () -> mock(RuleAnswerRateLimiter.class));
            context.register(AnswerEvidenceAgent.class);

            context.refresh();

            assertThat(context.getBean(AnswerEvidenceRefiner.class))
                    .isInstanceOf(AnswerEvidenceAgent.class);
        }
    }

    @Test
    void keepsTheFastPathForAReadyDirectQuestionWithoutCallingTheNativeAgent() {
        AtomicInteger calls = new AtomicInteger();
        NativeToolAgent nativeAgent = request -> {
            calls.incrementAndGet();
            throw new AssertionError("direct evidence must not invoke the native Agent");
        };
        DocumentNativeToolScopeFactory scopes = mock(DocumentNativeToolScopeFactory.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes, limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(hit(UUID.randomUUID(), "Movement", "Move one space."));

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId, question("How do I move?"), new QuestionContext(versionId), "player", null, deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(calls).hasValue(0);
        verify(scopes, never()).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(limiter, never()).acquireModel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hydratesToolObservedEvidenceAndReusesTheAnswerModelPermitBoundary() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit later = source(UUID.randomUUID(), "Payment", "Pay after completing movement.");
        NativeToolAgent nativeAgent = fixedAgent(completed(later.chunkId()));
        DocumentNativeToolScopeFactory scopes = scopes();
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel(
                        org.mockito.ArgumentMatchers.eq("player"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(permit);
        RuleEvidenceLookup lookup = (documentVersionId, chunkIds) -> List.of(later);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, lookup, scopes, limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(initial));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .contains(later.chunkId(), initial.evidence().chunkId());
        verify(limiter).acquireModel(
                org.mockito.ArgumentMatchers.eq("player"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString());
        verify(permit).close();
    }

    @Test
    void refinesASingleQuestionWhenSelectedEvidenceHasNoDirectQuestionAnchor() {
        AtomicInteger calls = new AtomicInteger();
        NativeToolAgent nativeAgent = request -> {
            calls.incrementAndGet();
            return new RunResult(RunStatus.COMPLETED, "EVIDENCE_READY", "MODEL_COMPLETED", 1, 1, List.of());
        };
        DocumentNativeToolScopeFactory scopes = scopes();
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel(
                        org.mockito.ArgumentMatchers.eq("player"),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes, limiter);
        AnswerEvidenceRetriever.Result weak = ready(hit(
                UUID.randomUUID(), "Components", "Put every marker beside the board."));

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("How is the winner determined?"),
                new QuestionContext(versionId),
                "player",
                null,
                weak);

        assertThat(result).isSameAs(weak);
        assertThat(calls).hasValue(1);
        verify(limiter).acquireModel(
                org.mockito.ArgumentMatchers.eq("player"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString());
        verify(permit).close();
    }

    @Test
    void preservesDeterministicEvidenceWhenTheNativeLoopFallsBack() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit ignored = source(UUID.randomUUID(), "Payment", "Pay after movement.");
        RunResult fallback = new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "EXECUTION_FAILED",
                2,
                1,
                List.of(observation(ignored.chunkId())));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AtomicInteger hydrationCalls = new AtomicInteger();
        RuleEvidenceLookup lookup = (documentVersionId, chunkIds) -> {
            hydrationCalls.incrementAndGet();
            return List.of(ignored);
        };
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(fixedAgent(fallback), lookup, scopes(), limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(initial);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(hydrationCalls).hasValue(0);
        verify(permit).close();
    }

    @Test
    void preservesCanonicalExactPageEvidenceWhenOnlyTheFinalModelTurnIsEmpty() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit exact = source(UUID.randomUUID(), "Payment", "Pay after completing movement.");
        ToolObservation pageObservation = ToolObservation.success(
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", exact.chunkId().toString()))),
                1);
        RunResult partial = new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "EMPTY_MODEL_RESULT",
                4,
                2,
                List.of(new ObservationRecord(3, "read_rule_pages", "page-schema", pageObservation)));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(partial),
                (documentVersionId, chunkIds) -> List.of(exact),
                scopes(),
                limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(initial));

        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .contains(exact.chunkId(), initial.evidence().chunkId());
        verify(permit).close();
    }

    @Test
    void preservesCanonicalExactPageEvidenceWhenOnlyTheTerminalProtocolIsRejected() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit exact = source(UUID.randomUUID(), "Payment", "Pay after completing movement.");
        ToolObservation pageObservation = ToolObservation.success(
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", exact.chunkId().toString()))),
                1);
        RunResult partial = new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "COMPLETION_PROTOCOL_REJECTED",
                4,
                2,
                List.of(new ObservationRecord(3, "read_rule_pages", "page-schema", pageObservation)));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(partial),
                (documentVersionId, chunkIds) -> List.of(exact),
                scopes(),
                limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(initial));

        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .contains(exact.chunkId(), initial.evidence().chunkId());
        verify(permit).close();
    }

    @Test
    void refusesToRecoverARejectedTerminalProtocolFromSearchOnlyObservations() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit searchOnly = source(UUID.randomUUID(), "Payment", "Pay after movement.");
        ToolObservation searchObservation = ToolObservation.success(
                "EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", searchOnly.chunkId().toString()))),
                1);
        RunResult partial = new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "COMPLETION_PROTOCOL_REJECTED",
                4,
                2,
                List.of(new ObservationRecord(3, "search_rule_evidence", "search-schema", searchObservation)));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AtomicInteger hydrationCalls = new AtomicInteger();
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(partial),
                (documentVersionId, chunkIds) -> {
                    hydrationCalls.incrementAndGet();
                    return List.of(searchOnly);
                },
                scopes(),
                limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(initial);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(hydrationCalls).hasValue(0);
        verify(permit).close();
    }

    @Test
    void ignoresMalformedAndOutOfScopeObservationIdentifiers() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        UUID missing = UUID.randomUUID();
        ToolObservation observation = new ToolObservation(
                ObservationStatus.SUCCESS,
                "EVIDENCE_FOUND",
                Map.of("evidence", List.of(
                        Map.of("evidenceId", "not-a-uuid"),
                        Map.of("evidenceId", missing.toString()))),
                2);
        RunResult completed = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                2,
                1,
                List.of(new ObservationRecord(1, "search_rule_evidence", "schema", observation)));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(fixedAgent(completed), emptyLookup(), scopes(), limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(initial);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                deterministic);

        assertThat(result).isSameAs(deterministic);
    }

    @Test
    void prioritizesTheLaterExactPageObservationIndependentOfRepositoryOrder() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General turn overview.");
        RuleEvidenceHit broad = source(UUID.randomUUID(), "Search result", "Broad condition summary.");
        RuleEvidenceHit exact = source(UUID.randomUUID(), "Exact page", "The exact exception and its timing.");
        ObservationRecord search = observation(broad.chunkId());
        ToolObservation pageObservation = ToolObservation.success(
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", exact.chunkId().toString()))),
                1);
        ObservationRecord page = new ObservationRecord(
                2, "read_rule_pages", "page-schema", pageObservation);
        RunResult completed = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                3,
                2,
                List.of(search, page));
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        RuleEvidenceLookup reverseRepositoryOrder = (documentVersionId, chunkIds) -> List.of(broad, exact);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed), reverseRepositoryOrder, scopes(), limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("Can I move, and what exact exception changes the timing?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(initial));

        assertThat(result.evidence()).first().extracting(hit -> hit.evidence().chunkId()).isEqualTo(exact.chunkId());
    }

    @Test
    void replacesDerivedVisualExcerptWithCanonicalObservedEvidenceForTheSameChunk() {
        UUID chunkId = UUID.randomUUID();
        HybridEvidenceHit visuallyEnriched = hit(
                chunkId,
                "Game end",
                "Visual facts: a marker reaches the end.\n\nCanonical text: End the game immediately.");
        RuleEvidenceHit canonical = source(chunkId, "Game end", "End the game immediately.");
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed(chunkId)),
                (documentVersionId, chunkIds) -> List.of(canonical),
                scopes(),
                limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("When does the game end, and who wins?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(visuallyEnriched));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).extracting(hit -> hit.evidence().excerpt())
                .contains("End the game immediately.")
                .doesNotContain(visuallyEnriched.evidence().excerpt());
    }

    @Test
    void preservesPageBoundVisualFactsWhenTheCanonicalSourceIsOnlyAVisualPlaceholder() {
        UUID chunkId = UUID.randomUUID();
        HybridEvidenceHit visuallyEnriched = hit(
                chunkId,
                "Visual rulebook page 5",
                "Visual page facts (verify against the cited rulebook page).\nVisible facts: Draft one point card or two vegetable cards.");
        RuleEvidenceHit placeholder = source(
                chunkId,
                "Visual rulebook page 5",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.");
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed(chunkId)),
                (documentVersionId, chunkIds) -> List.of(placeholder),
                scopes(),
                limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("What can I draft, and what happens next?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(visuallyEnriched));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).extracting(hit -> hit.evidence().excerpt())
                .contains(visuallyEnriched.evidence().excerpt())
                .doesNotContain(placeholder.excerpt());
    }

    @Test
    void rejectsAnObservedChunkWhoseImmutableCoordinatesDisagreeWithTheExistingEvidence() {
        UUID chunkId = UUID.randomUUID();
        HybridEvidenceHit existing = hit(chunkId, "Game end", "End the game immediately.");
        RuleEvidenceHit conflicting = new RuleEvidenceHit(
                chunkId, versionId, "RULES", "Game end", "End the game immediately.", 3, 3, 0.9);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed(chunkId)),
                (documentVersionId, chunkIds) -> List.of(conflicting),
                scopes(),
                limiter);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question("When does the game end, and who wins?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(existing));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.CONFLICTING);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void passesBoundedPriorProvenanceAsAReferenceHintAndRequiresFreshObservation() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General turn overview.");
        RuleEvidenceHit priorSource = source(UUID.randomUUID(), "Exception", "The verified exception timing.");
        java.util.concurrent.atomic.AtomicReference<NativeToolAgent.RunRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolAgent nativeAgent = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                captured.set(request);
                return completed(priorSource.chunkId());
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return "test-provider";
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        var reference = new PriorTurnReference(
                versionId,
                "When does the phase end?",
                "It ends after resolving the exception.",
                List.of(new PriorCitationReference(priorSource.chunkId(), versionId, 2, 2)));
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                nativeAgent, (documentVersionId, chunkIds) -> List.of(priorSource), scopes(), limiter);

        var result = agent.refine(
                runId,
                question("Why does that happen? Give a simpler example."),
                new QuestionContext(versionId, reference.question(), null, null, reference),
                "player",
                null,
                ready(initial));

        assertThat(captured.get().playerRequest())
                .contains("Prior grounded reference hint (not current evidence)")
                .contains(priorSource.chunkId().toString())
                .contains("Prior cited pages to re-read: [2]")
                .contains("read_rule_pages once");
        assertThat(captured.get().allowedTools())
                .containsExactly("read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion())
                .containsExactly("read_rule_pages");
        assertThat(captured.get().maxIterations()).isEqualTo(2);
        assertThat(captured.get().maxToolCalls()).isEqualTo(1);
        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId()).contains(priorSource.chunkId());
    }

    @Test
    void keepsTheSearchPortfolioWhenASessionQuestionDoesNotReferToThePriorTurn() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General turn overview.");
        java.util.concurrent.atomic.AtomicReference<NativeToolAgent.RunRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolAgent nativeAgent = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                captured.set(request);
                return new RunResult(
                        RunStatus.FALLBACK,
                        "EVIDENCE_REFINEMENT_UNAVAILABLE",
                        "NO_NEW_EVIDENCE",
                        1,
                        0,
                        List.of());
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return "test-provider";
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        var reference = new PriorTurnReference(
                versionId,
                "When does the phase end?",
                "It ends after resolving the exception.",
                List.of(new PriorCitationReference(UUID.randomUUID(), versionId, 2, 2)));
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes(), limiter);

        agent.refine(
                runId,
                question("How many cards do players draw during setup?"),
                new QuestionContext(versionId, reference.question(), null, null, reference),
                "player",
                null,
                ready(initial));

        assertThat(captured.get().allowedTools()).containsExactlyInAnyOrder(
                "search_rule_evidence", "expand_rule_evidence_context", "read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion()).isEmpty();
        assertThat(captured.get().maxToolCalls()).isEqualTo(4);
        assertThat(captured.get().playerRequest()).doesNotContain("Prior cited pages to re-read");
    }

    @Test
    void selectsTheRelationshipPortfolioInsteadOfAdvertisingEveryAnswerTool() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General rule overview.");
        java.util.concurrent.atomic.AtomicReference<NativeToolAgent.RunRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolAgent nativeAgent = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                captured.set(request);
                return new RunResult(
                        RunStatus.FALLBACK,
                        "EVIDENCE_REFINEMENT_UNAVAILABLE",
                        "NO_NEW_EVIDENCE",
                        1,
                        0,
                        List.of());
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return "test-provider";
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes(), limiter);

        agent.refine(
                runId,
                question("Does the special rule override the general rule?"),
                new QuestionContext(versionId),
                "player",
                null,
                ready(initial));

        assertThat(captured.get().allowedTools()).containsExactlyInAnyOrder(
                "search_rule_evidence",
                "search_rule_relationships",
                "expand_rule_evidence_context",
                "read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion()).isEmpty();
        assertThat(captured.get().maxToolCalls()).isEqualTo(4);
    }

    @Test
    void letsTheValidatedQuestionPlanDriveAMultiObligationToolPortfolio() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General rule overview.");
        java.util.concurrent.atomic.AtomicReference<NativeToolAgent.RunRequest> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        NativeToolAgent nativeAgent = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                captured.set(request);
                return new RunResult(
                        RunStatus.FALLBACK,
                        "EVIDENCE_REFINEMENT_UNAVAILABLE",
                        "NO_NEW_EVIDENCE",
                        1,
                        0,
                        List.of());
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return "test-provider";
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes(), limiter);
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(
                        new AnswerQuestionPlan.Subquestion(
                                "哪个规则优先", Set.of(EvidenceNeed.RELATIONSHIP)),
                        new AnswerQuestionPlan.Subquestion(
                                "图标表示什么", Set.of(EvidenceNeed.VISUAL_REFERENCE))),
                true);

        agent.refine(
                runId,
                question("哪个规则优先，图标表示什么？"),
                new QuestionContext(versionId),
                "player",
                null,
                plan,
                ready(initial));

        assertThat(captured.get().allowedTools()).containsExactlyInAnyOrder(
                "search_rule_evidence",
                "search_rule_relationships",
                "expand_rule_evidence_context",
                "read_visual_page_facts",
                "read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion()).isEmpty();
        assertThat(captured.get().maxIterations()).isEqualTo(5);
        assertThat(captured.get().maxToolCalls()).isEqualTo(5);
        assertThat(captured.get().playerRequest())
                .contains("Agent-validated question plan", "哪个规则优先", "RELATIONSHIP", "图标表示什么", "VISUAL_REFERENCE");
    }

    @Test
    void refusesToPromoteSearchOnlyObservationsWithoutAnExactPageRead() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Overview", "General rule overview.");
        RuleEvidenceHit searchOnly = source(UUID.randomUUID(), "Exception", "A candidate exception.");
        ToolObservation observation = ToolObservation.success(
                "EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", searchOnly.chunkId().toString()))),
                1);
        RunResult completed = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                2,
                1,
                List.of(new ObservationRecord(1, "search_rule_evidence", "schema", observation)));
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        Permit permit = mock(Permit.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        AtomicInteger hydrationCalls = new AtomicInteger();
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed),
                (documentVersionId, chunkIds) -> {
                    hydrationCalls.incrementAndGet();
                    return List.of(searchOnly);
                },
                scopes(),
                limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(initial);

        var result = agent.refine(
                runId,
                question("Is there an exception, and when does it apply?"),
                new QuestionContext(versionId),
                "player",
                null,
                deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(hydrationCalls).hasValue(0);
    }

    @Test
    void deterministicallyDowngradesBeforePermitOrProviderCallWhenNativeCapabilityIsUnavailable() {
        AtomicInteger calls = new AtomicInteger();
        NativeToolAgent unavailable = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                calls.incrementAndGet();
                throw new AssertionError("unavailable provider must not run");
            }

            @Override
            public boolean supports(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return false;
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(unavailable, emptyLookup(), scopes(), limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(hit(UUID.randomUUID(), "Overview", "General rule."));

        var result = agent.refine(
                runId,
                question("Why does that happen?"),
                new QuestionContext(versionId, "When does it happen?", null, null),
                "player",
                null,
                deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(calls).hasValue(0);
        verify(limiter, never()).acquireModel(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private NativeToolAgent fixedAgent(RunResult result) {
        return new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                return result;
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return "test-provider";
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
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", evidenceId.toString()))),
                1);
        return new ObservationRecord(1, "read_rule_pages", "schema", observation);
    }

    private DocumentNativeToolScopeFactory scopes() {
        DocumentNativeToolScopeFactory scopes = mock(DocumentNativeToolScopeFactory.class);
        when(scopes.create("player", versionId, runId)).thenReturn(Optional.of(
                new ToolScope("player", versionId, runId, Instant.now().plusSeconds(30))));
        return scopes;
    }

    private UnderstoodQuestion question(String value) {
        return new UnderstoodQuestion(
                versionId, value, value, QuestionType.RULE_QUERY, List.of(), Set.of());
    }

    private AnswerEvidenceRetriever.Result ready(HybridEvidenceHit... evidence) {
        return new AnswerEvidenceRetriever.Result(List.of(evidence), AnswerEvidenceRetriever.State.READY);
    }

    private HybridEvidenceHit hit(UUID id, String heading, String excerpt) {
        RuleEvidenceHit source = source(id, heading, excerpt);
        return new HybridEvidenceHit(source, source.score(), 1, null, false);
    }

    private RuleEvidenceHit source(UUID id, String heading, String excerpt) {
        return new RuleEvidenceHit(id, versionId, "RULES", heading, excerpt, 2, 2, 0.9);
    }

    private RuleEvidenceLookup emptyLookup() {
        return (documentVersionId, chunkIds) -> List.of();
    }
}
