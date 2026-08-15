package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
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
import java.util.concurrent.atomic.AtomicReference;
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

            assertThat(context.getBean(AnswerEvidenceRefiner.class)).isInstanceOf(AnswerEvidenceAgent.class);
        }
    }

    @Test
    void keepsTheFastPathForOneReadyDirectRuleObligation() {
        AtomicInteger calls = new AtomicInteger();
        NativeToolAgent nativeAgent = request -> {
            calls.incrementAndGet();
            throw new AssertionError("fast path must not invoke the native Agent");
        };
        DocumentNativeToolScopeFactory scopes = mock(DocumentNativeToolScopeFactory.class);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(nativeAgent, emptyLookup(), scopes, limiter);
        AnswerEvidenceRetriever.Result deterministic = ready(hit(UUID.randomUUID(), "Movement", "Move one space."));

        var result = agent.refine(
                runId,
                question("How do I move?"),
                new QuestionContext(versionId),
                "player",
                null,
                plan(Set.of(EvidenceNeed.DIRECT_RULE)),
                deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(calls).hasValue(0);
        verify(scopes, never()).create(any(), any(), any());
        verify(limiter, never()).acquireModel(any(), any(), any());
    }

    @Test
    void hydratesOnlyCanonicalEvidenceFromExactPageObservationsAndClosesThePermit() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit observed = source(UUID.randomUUID(), "Payment", "Pay after movement.");
        Permit permit = mock(Permit.class);
        RuleAnswerRateLimiter limiter = limiter(permit);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed(observed.chunkId())),
                (documentVersionId, ids) -> List.of(observed),
                scopes(),
                limiter);

        var result = agent.refine(
                runId,
                question("Can I move, and when do I pay?"),
                new QuestionContext(versionId),
                "player",
                null,
                multiObligationPlan(),
                ready(initial));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .contains(observed.chunkId(), initial.evidence().chunkId());
        verify(permit).close();
    }

    @Test
    void preservesExactPageEvidenceFromARecoverablePartialRun() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit observed = source(UUID.randomUUID(), "Payment", "Pay after movement.");
        RunResult partial = new RunResult(
                RunStatus.FALLBACK,
                "EVIDENCE_REFINEMENT_UNAVAILABLE",
                "EMPTY_MODEL_RESULT",
                3,
                1,
                List.of(observation(observed.chunkId())));
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(partial),
                (documentVersionId, ids) -> List.of(observed),
                scopes(),
                limiter(permit));

        var result = agent.refine(
                runId, question("What is the exception?"), new QuestionContext(versionId),
                "player", null, plan(Set.of(EvidenceNeed.EXCEPTION)), ready(initial));

        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                .contains(observed.chunkId(), initial.evidence().chunkId());
        verify(permit).close();
    }

    @Test
    void neverPromotesSearchOnlyHandlesAndPreservesDeterministicEvidence() {
        HybridEvidenceHit initial = hit(UUID.randomUUID(), "Movement", "Move one space.");
        RuleEvidenceHit searchOnly = source(UUID.randomUUID(), "Candidate", "Candidate exception.");
        ToolObservation search = ToolObservation.success(
                "EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", searchOnly.chunkId().toString()))),
                1);
        RunResult completedWithoutPageRead = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                2,
                1,
                List.of(new ObservationRecord(1, "search_rule_evidence", "schema", search)));
        AtomicInteger hydrationCalls = new AtomicInteger();
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completedWithoutPageRead),
                (documentVersionId, ids) -> {
                    hydrationCalls.incrementAndGet();
                    return List.of(searchOnly);
                },
                scopes(),
                limiter(permit));
        AnswerEvidenceRetriever.Result deterministic = ready(initial);

        var result = agent.refine(
                runId, question("What is the exception?"), new QuestionContext(versionId),
                "player", null, plan(Set.of(EvidenceNeed.EXCEPTION)), deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(hydrationCalls).hasValue(0);
        verify(permit).close();
    }

    @Test
    void rejectsAnObservedChunkWhoseImmutableIdentityChanged() {
        UUID sharedId = UUID.randomUUID();
        HybridEvidenceHit existing = hit(sharedId, "Original heading", "Original text.");
        RuleEvidenceHit changed = new RuleEvidenceHit(
                sharedId, versionId, "RULES", "Changed heading", "Canonical text.", 9, 9, 0.9);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                fixedAgent(completed(sharedId)),
                (documentVersionId, ids) -> List.of(changed),
                scopes(),
                limiter(mock(Permit.class)));

        var result = agent.refine(
                runId, question("What is the exception?"), new QuestionContext(versionId),
                "player", null, plan(Set.of(EvidenceNeed.EXCEPTION)), ready(existing));

        assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.CONFLICTING);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void exposesOnlyToolsRequiredByTheAcceptedStructuredEvidenceNeeds() {
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        NativeToolAgent nativeAgent = capturingFallbackAgent(captured);
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                nativeAgent, emptyLookup(), scopes(), limiter(permit));
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        "Compare the printed relationship.",
                        Set.of(EvidenceNeed.RELATIONSHIP, EvidenceNeed.VISUAL_REFERENCE))),
                true,
                AnswerAid.CONCEPT_COMPARISON,
                ReferenceBinding.CURRENT_QUESTION);

        agent.refine(
                runId, question("Compare these printed rules."), new QuestionContext(versionId),
                "player", null, plan, ready(hit(UUID.randomUUID(), "Overview", "Overview.")));

        assertThat(captured.get().allowedTools()).containsExactlyInAnyOrder(
                "search_rule_evidence",
                "expand_rule_evidence_context",
                "read_rule_pages",
                "search_rule_relationships",
                "read_visual_page_facts");
        assertThat(captured.get().requiredToolsBeforeCompletion()).isEmpty();
        assertThat(captured.get().playerRequest())
                .contains("evidence needs: ", "RELATIONSHIP", "VISUAL_REFERENCE");
        verify(permit).close();
    }

    @Test
    void requiresAnExactPageAuditForAConcreteCalculation() {
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                capturingFallbackAgent(captured), emptyLookup(), scopes(), limiter(permit));
        AnswerQuestionPlan calculation = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        "I have two cards and nine spaces; what is the total?",
                        Set.of(EvidenceNeed.DIRECT_RULE))),
                true,
                AnswerAid.CALCULATION,
                ReferenceBinding.CURRENT_QUESTION);

        agent.refine(
                runId,
                question("I have two cards and nine spaces; what is the total?"),
                new QuestionContext(versionId),
                "player",
                null,
                calculation,
                ready(hit(UUID.randomUUID(), "Scoring", "Each matching space scores one point.")));

        assertThat(captured.get().requiredToolsBeforeCompletion()).containsExactly("read_rule_pages");
        assertThat(captured.get().systemPrompt()).contains(
                "aggregation unit",
                "per-item or per-category scope",
                "multiplier",
                "worked example",
                "consistency check");
        verify(permit).close();
    }

    @Test
    void treatsAdviceAsASeparateSourceEvidenceNeed() {
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                capturingFallbackAgent(captured), emptyLookup(), scopes(), limiter(permit));

        agent.refine(
                runId,
                question("有没有更容易赢的打法或建议？"),
                new QuestionContext(versionId),
                "player",
                null,
                plan(Set.of(EvidenceNeed.ADVICE)),
                ready(hit(UUID.randomUUID(), "Objective", "The first player to 30 points wins.")));

        assertThat(captured.get().playerRequest()).contains(
                "evidence needs: [ADVICE]",
                "Independent application-owned advice source-cue searches",
                "preferred choice ideal should recommendation advice",
                "caution avoid warning watch out");
        assertThat(captured.get().systemPrompt()).contains(
                "source-authored recommendations",
                "victory",
                "condition, scoring route, or legal action is not itself advice");
        assertThat(captured.get().requiredToolsBeforeCompletion()).containsExactly("read_rule_pages");
        assertThat(captured.get().maxIterations()).isEqualTo(5);
        assertThat(captured.get().maxToolCalls()).isEqualTo(5);
        verify(permit).close();
    }

    @Test
    void priorGroundedReferenceCanOnlyTriggerOneFreshExactPageRead() {
        RuleEvidenceHit prior = source(UUID.randomUUID(), "Exception", "Verified exception timing.");
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        NativeToolAgent nativeAgent = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                captured.set(request);
                return completed(prior.chunkId());
            }

            @Override
            public String providerId(Role role, String ownerUsername) {
                return "test-provider";
            }
        };
        PriorTurnReference reference = new PriorTurnReference(
                versionId,
                "When does the phase end?",
                "It ends after the exception.",
                List.of(new PriorCitationReference(prior.chunkId(), versionId, 2, 2)));
        QuestionContext context = new QuestionContext(
                versionId, reference.question(), null, null, reference);
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        "Why does that happen?", Set.of(EvidenceNeed.PRIOR_TURN))),
                true,
                AnswerAid.WALKTHROUGH,
                ReferenceBinding.PRIOR_GROUNDED_TURN);
        Permit permit = mock(Permit.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                nativeAgent, (documentVersionId, ids) -> List.of(prior), scopes(), limiter(permit));

        var result = agent.refine(
                runId, question("Why does that happen?"), context,
                "player", null, plan, ready(hit(UUID.randomUUID(), "Overview", "Overview.")));

        assertThat(captured.get().allowedTools()).containsExactly("read_rule_pages");
        assertThat(captured.get().requiredToolsBeforeCompletion()).containsExactly("read_rule_pages");
        assertThat(captured.get().maxIterations()).isEqualTo(2);
        assertThat(captured.get().maxToolCalls()).isEqualTo(1);
        assertThat(captured.get().playerRequest()).contains(
                "Prior grounded reference hint (not current evidence)",
                prior.chunkId().toString(),
                "Prior cited pages to re-read: [2]");
        assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId()).contains(prior.chunkId());
        verify(permit).close();
    }

    @Test
    void downgradesBeforePermitAcquisitionWhenNativeCapabilityIsUnavailable() {
        AtomicInteger runs = new AtomicInteger();
        NativeToolAgent unavailable = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                runs.incrementAndGet();
                throw new AssertionError("unsupported provider must not run");
            }

            @Override
            public boolean supports(Role role, String ownerUsername) {
                return false;
            }
        };
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        AnswerEvidenceAgent agent = new AnswerEvidenceAgent(
                unavailable, emptyLookup(), scopes(), limiter);
        AnswerEvidenceRetriever.Result deterministic = ready();

        var result = agent.refine(
                runId, question("What is the exception?"), new QuestionContext(versionId),
                "player", null, plan(Set.of(EvidenceNeed.EXCEPTION)), deterministic);

        assertThat(result).isSameAs(deterministic);
        assertThat(runs).hasValue(0);
        verify(limiter, never()).acquireModel(any(), any(), any());
    }

    private AnswerQuestionPlan plan(Set<EvidenceNeed> needs) {
        return new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion("Current rule obligation", needs)),
                true,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
    }

    private AnswerQuestionPlan multiObligationPlan() {
        return new AnswerQuestionPlan(
                List.of(
                        new AnswerQuestionPlan.Subquestion("Movement rule", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new AnswerQuestionPlan.Subquestion("Payment timing", Set.of(EvidenceNeed.SEQUENCE))),
                true,
                AnswerAid.WALKTHROUGH,
                ReferenceBinding.CURRENT_QUESTION);
    }

    private NativeToolAgent fixedAgent(RunResult result) {
        return new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                return result;
            }

            @Override
            public String providerId(Role role, String ownerUsername) {
                return "test-provider";
            }
        };
    }

    private NativeToolAgent capturingFallbackAgent(AtomicReference<RunRequest> captured) {
        return new NativeToolAgent() {
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
            public String providerId(Role role, String ownerUsername) {
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

    private RuleAnswerRateLimiter limiter(Permit permit) {
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel("player", null, "test-provider")).thenReturn(permit);
        return limiter;
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
