package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.ObservationStatus;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolAgent.TerminalValidation;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ModelTurn;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeRuleAnswerAgentTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void publishesOneTypedGreetingTurnWithoutAnyReadOrPresenterRewrite() {
        String terminal = """
                {"kind":"CHAT","shortVerdict":"你好！很高兴帮你查规则。","explanation":"", "tone":"warm"}
                """.strip();
        StubNativeAgent nativeAgent = new StubNativeAgent(terminal, List.of(), 0);
        NativeRuleAnswerAgent answers = answers(nativeAgent, emptyLookup());

        var outcome = answers.answer(
                "你好", new QuestionContext(versionId), "player", null, runId);

        assertThat(nativeAgent.runs).hasValue(1);
        assertThat(outcome.toolCalls()).isZero();
        assertThat(outcome.answer().status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(outcome.answer().shortVerdict()).isEqualTo("你好！很高兴帮你查规则。");
        assertThat(outcome.answer().explanation()).isEmpty();
        assertThat(outcome.answer().citations()).isEmpty();
        assertThat(outcome.answer().answerBasis()).isNull();
        assertThat(nativeAgent.request.systemPrompt()).contains("\"CHAT\"", "additionalProperties");
    }

    @Test
    void publishesCanonicalExactReadEvidenceAndKeepsACompletedSiblingWhenAnotherReadFailed() {
        UUID evidenceId = UUID.randomUUID();
        String excerpt = "The game is played in 5 rounds. At the end of round 5, the player with the most points wins.";
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                evidenceId, versionId, "OVERVIEW", "Game overview", excerpt, 7, 7, 1.0);
        List<ObservationRecord> observations = List.of(
                exactRead(evidenceId, excerpt, 7),
                new ObservationRecord(
                        1,
                        "crop_rule_page_image",
                        "crop-schema",
                        new ToolObservation(ObservationStatus.ERROR, "TOOL_EXECUTION_FAILED", Map.of(), 0)));
        String terminal = """
                {
                  "kind":"RULE_ANSWER",
                  "shortVerdict":"游戏共 5 轮。",
                  "explanation":"第 5 轮结束后计算最终得分，得分最高者获胜。",
                  "citationIds":["%s"],
                  "numericClaims":[{"value":"5","evidenceId":"%s","note":"additive"}],
                  "exceptions":[],
                  "presentationHint":"concise"
                }
                """.formatted(evidenceId, evidenceId);
        StubNativeAgent nativeAgent = new StubNativeAgent(terminal, observations, 2);
        NativeRuleAnswerAgent answers = answers(nativeAgent, lookup(evidence));

        var outcome = answers.answer(
                "这局有几轮，怎么决定赢家？",
                new QuestionContext(versionId),
                "player",
                null,
                runId);

        assertThat(outcome.answer().status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(outcome.answer().answerBasis()).isEqualTo(AnswerBasis.DIRECT_RULE);
        assertThat(outcome.answer().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(evidenceId);
            assertThat(citation.pageFrom()).isEqualTo(7);
            assertThat(citation.excerpt()).isEqualTo(excerpt);
        });
        assertThat(outcome.answer().shortVerdict()).isEqualTo("游戏共 5 轮。");
        assertThat(outcome.answer().explanation()).isEqualTo("第 5 轮结束后计算最终得分，得分最高者获胜。");
    }

    @Test
    void rejectsAnUnobservedIdentityWithTheCurrentAllowedEvidenceSet() {
        UUID allowed = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                allowed, versionId, "RULES", "Movement", "Move one space.", 4, 4, 1.0);
        NativeRuleAnswerAgent answers = answers(
                new StubNativeAgent("unused", List.of(), 0), lookup(evidence));
        String candidate = """
                {"kind":"RULE_ANSWER","shortVerdict":"Move.","explanation":"Allowed.",
                 "citationIds":["%s"]}
                """.formatted(outside);

        TerminalValidation result = answers.validateTerminal(
                candidate, List.of(exactRead(allowed, evidence.excerpt(), 4)), new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("CITATION_NOT_OBSERVED");
        assertThat(result.path()).isEqualTo("/citationIds/0");
        assertThat(result.allowedEvidenceIds()).containsExactly(allowed.toString());
    }

    @Test
    void publishesSourceBearingSearchEvidenceWithOptionalExplanationAfterTwoModelDecisions() {
        UUID evidenceId = UUID.randomUUID();
        String excerpt = "Pay 2 fuel to move one space.";
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                evidenceId, versionId, "MOVEMENT", "Fuel movement", excerpt, 6, 6, 1.0);
        String terminal = """
                {"kind":"RULE_ANSWER","shortVerdict":"Pay 2 fuel, then move one space.","explanation":"",
                 "citationIds":["%s"],"numericClaims":[{"value":"2","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId).strip();
        AtomicInteger decisions = new AtomicInteger();
        NativeRuleAnswerAgent answers = answers(twoDecisionSearchAgent(evidence, terminal, decisions), lookup(evidence));

        var outcome = answers.answer(
                "How does fuel movement work?",
                new QuestionContext(versionId),
                "player",
                null,
                runId);

        assertThat(decisions).hasValue(2);
        assertThat(outcome.toolCalls()).isOne();
        assertThat(outcome.answer().status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(outcome.answer().shortVerdict()).isEqualTo("Pay 2 fuel, then move one space.");
        assertThat(outcome.answer().explanation()).isEmpty();
        assertThat(outcome.answer().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(evidenceId);
            assertThat(citation.pageFrom()).isEqualTo(6);
            assertThat(citation.excerpt()).isEqualTo(excerpt);
        });
    }

    @Test
    void rejectsASearchIdentityThatWasNotObserved() {
        UUID observed = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                observed, versionId, "MOVEMENT", "Fuel movement", "Pay 2 fuel to move one space.", 6, 6, 1.0);
        NativeRuleAnswerAgent answers = answers(new StubNativeAgent("unused", List.of(), 0), lookup(evidence));

        TerminalValidation result = answers.validateTerminal(
                """
                {"kind":"RULE_ANSWER","shortVerdict":"Pay fuel and move.","explanation":"",
                 "citationIds":["%s"]}
                """.formatted(outside),
                List.of(searchObservation(observed, evidence.excerpt(), 6)),
                new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("CITATION_NOT_OBSERVED");
        assertThat(result.allowedEvidenceIds()).containsExactly(observed.toString());
    }

    @Test
    void rejectsSearchEvidenceWhoseCanonicalDocumentVersionDiffers() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidenceHit crossVersion = new RuleEvidenceHit(
                evidenceId,
                UUID.randomUUID(),
                "MOVEMENT",
                "Fuel movement",
                "Pay 2 fuel to move one space.",
                6,
                6,
                1.0);
        NativeRuleAnswerAgent answers = answers(new StubNativeAgent("unused", List.of(), 0), lookup(crossVersion));

        TerminalValidation result = answers.validateTerminal(
                """
                {"kind":"RULE_ANSWER","shortVerdict":"Pay 2 fuel, then move one space.","explanation":"",
                 "citationIds":["%s"],"numericClaims":[{"value":"2","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId),
                List.of(searchObservation(evidenceId, crossVersion.excerpt(), 6)),
                new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("CITATION_DOCUMENT_CONFLICT");
    }

    @Test
    void rejectsSearchEvidenceWhoseObservedPageDiffersFromTheCanonicalSnapshot() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidenceHit canonical = new RuleEvidenceHit(
                evidenceId,
                versionId,
                "MOVEMENT",
                "Fuel movement",
                "Pay 2 fuel to move one space.",
                7,
                7,
                1.0);
        NativeRuleAnswerAgent answers = answers(new StubNativeAgent("unused", List.of(), 0), lookup(canonical));

        TerminalValidation result = answers.validateTerminal(
                """
                {"kind":"RULE_ANSWER","shortVerdict":"Pay 2 fuel, then move one space.","explanation":"",
                 "citationIds":["%s"],"numericClaims":[{"value":"2","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId),
                List.of(searchObservation(evidenceId, canonical.excerpt(), 6)),
                new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("CITATION_SNAPSHOT_CONFLICT");
    }

    @Test
    void rejectsATypedHardNumberAbsentFromItsCitedEvidence() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                evidenceId, versionId, "LIMIT", "Hand limit", "Keep at most 4 cards.", 9, 9, 1.0);
        NativeRuleAnswerAgent answers = answers(
                new StubNativeAgent("unused", List.of(), 0), lookup(evidence));
        String candidate = """
                {"kind":"RULE_ANSWER","shortVerdict":"Keep 5 cards.","explanation":"That is the limit.",
                 "citationIds":["%s"],"numericClaims":[{"value":"5","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId);

        TerminalValidation result = answers.validateTerminal(
                candidate, List.of(exactRead(evidenceId, evidence.excerpt(), 9)), new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("NUMERIC_VALUE_UNSUPPORTED");
        assertThat(result.path()).isEqualTo("/numericClaims/0/value");
    }

    @Test
    void validatesTypedNumericClaimsWithoutInterpretingPresentationDigitsAsRuleFacts() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                evidenceId, versionId, "LIMIT", "Hand limit", "Keep at most 4 cards.", 9, 9, 1.0);
        NativeRuleAnswerAgent answers = answers(
                new StubNativeAgent("unused", List.of(), 0), lookup(evidence));
        String candidate = """
                {"kind":"RULE_ANSWER","shortVerdict":"1. Keep at most 4 cards.","explanation":"That is the limit.",
                 "citationIds":["%s"],"numericClaims":[{"value":"4","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId);

        TerminalValidation result = answers.validateTerminal(
                candidate, List.of(exactRead(evidenceId, evidence.excerpt(), 9)), new QuestionContext(versionId));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsANumericClaimThatOnlyAppearsInsideAnotherNumber() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                evidenceId, versionId, "LIMIT", "Track limit", "Advance to space 15.", 9, 9, 1.0);
        NativeRuleAnswerAgent answers = answers(
                new StubNativeAgent("unused", List.of(), 0), lookup(evidence));
        String candidate = """
                {"kind":"RULE_ANSWER","shortVerdict":"Advance 5 spaces.","explanation":"Use the cited limit.",
                 "citationIds":["%s"],"numericClaims":[{"value":"5","evidenceId":"%s"}]}
                """.formatted(evidenceId, evidenceId);

        TerminalValidation result = answers.validateTerminal(
                candidate, List.of(exactRead(evidenceId, evidence.excerpt(), 9)), new QuestionContext(versionId));

        assertThat(result.valid()).isFalse();
        assertThat(result.code()).isEqualTo("NUMERIC_VALUE_UNSUPPORTED");
    }

    @Test
    void mapsTypedProviderFailuresToRetryablePublicStatusesWithoutChangingTheQuestion() {
        String question = "When may this effect resolve?";
        Map<String, AnswerStatus> expectedStatuses = Map.of(
                "MODEL_REQUEST_TIMEOUT", AnswerStatus.MODEL_TIMEOUT,
                "MODEL_REQUEST_UNAVAILABLE", AnswerStatus.MODEL_UNAVAILABLE);

        expectedStatuses.forEach((reason, expectedStatus) -> {
            NativeToolAgent failedAgent = request -> new RunResult(
                    RunStatus.FALLBACK,
                    request.fallbackText(),
                    reason,
                    1,
                    0,
                    List.of());
            NativeRuleAnswerAgent answers = answers(failedAgent, emptyLookup());

            var outcome = answers.answer(question, new QuestionContext(versionId), "player", null, runId);
            var presented = PlayerFacingAnswerPresenter.present(outcome.answer(), question, PlayerLocale.EN);

            assertThat(outcome.answer().status()).isEqualTo(expectedStatus);
            assertThat(presented.recovery()).isNotNull().satisfies(recovery -> {
                assertThat(recovery.draft()).isEqualTo(question);
                assertThat(recovery.canRetryUnchanged()).isTrue();
            });
        });
    }

    private NativeRuleAnswerAgent answers(NativeToolAgent agent, RuleEvidenceLookup lookup) {
        ToolScope scope = new ToolScope(
                "player", versionId, runId, Instant.now().plusSeconds(30));
        return new NativeRuleAnswerAgent(
                agent,
                (owner, document, run) -> java.util.Optional.of(scope),
                lookup,
                new RuleAnswerRateLimiter() {
                    @Override public void checkUser(String username) {}

                    @Override
                    public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                        return () -> {};
                    }
                },
                JsonMapper.builder().findAndAddModules().build());
    }

    private ObservationRecord exactRead(UUID evidenceId, String excerpt, int page) {
        return new ObservationRecord(
                1,
                "read_rule_pages",
                "page-schema",
                ToolObservation.success(
                        "PAGE_EVIDENCE_FOUND",
                        Map.of("evidence", List.of(Map.of(
                                "evidenceId", evidenceId.toString(),
                                "excerpt", excerpt,
                                "pageFrom", page,
                                "pageTo", page))),
                        1));
    }

    private ObservationRecord searchObservation(UUID evidenceId, String excerpt, int page) {
        return new ObservationRecord(
                1,
                "search_rule_evidence",
                "search-schema",
                ToolObservation.success(
                        "EVIDENCE_FOUND",
                        Map.of("evidence", List.of(Map.of(
                                "evidenceId", evidenceId.toString(),
                                "excerpt", excerpt,
                                "pageFrom", page,
                                "pageTo", page))),
                        1));
    }

    private NativeToolAgent twoDecisionSearchAgent(
            RuleEvidenceHit evidence, String terminal, AtomicInteger decisions) {
        NativeToolModel model = new NativeToolModel() {
            @Override
            public ModelTurn next(ModelRequest request) {
                int decision = decisions.incrementAndGet();
                if (decision == 1) {
                    assertThat(request.conversation().getFirst().content())
                            .contains(
                                    "cite that observed evidenceId without repeating the read",
                                    "Use read_rule_pages only when");
                    assertThat(request.tools())
                            .filteredOn(tool -> "search_rule_evidence".equals(tool.name()))
                            .singleElement()
                            .satisfies(tool -> assertThat(tool.description())
                                    .contains(
                                            "It may support a citation directly",
                                            "Read the exact page only when fuller page context"));
                }
                return switch (decision) {
                    case 1 -> new ModelTurn(
                            "",
                            List.of(new ModelToolCall(
                                    "search-1",
                                    "search_rule_evidence",
                                    "{\"query\":\"fuel movement\",\"limit\":1}")),
                            10,
                            5);
                    case 2 -> new ModelTurn(terminal, List.of(), 10, 5);
                    default -> throw new IllegalStateException("unexpected model decision");
                };
            }
        };
        var mapper = JsonMapper.builder().findAndAddModules().build();
        AssistantReadTools reads = request -> List.of(new RuleEvidence(
                evidence.chunkId(),
                evidence.documentVersionId(),
                evidence.sectionType(),
                evidence.heading(),
                evidence.excerpt(),
                evidence.pageFrom(),
                evidence.pageTo()));
        NativeAgentTool search = new SearchRuleEvidenceNativeTool(reads, mapper);
        List<NativeAgentTool> tools = NativeRuleAnswerAgent.READ_TOOLS.stream()
                .map(name -> search.name().equals(name) ? search : testTool(name))
                .toList();
        return new BoundedNativeToolAgent(
                model,
                new NativeAgentToolRegistry(tools, mapper, ignored -> true),
                mock(AgentExecutionControl.class),
                new ImmediateAuditedAgentInvocations(),
                mapper);
    }

    private NativeAgentTool testTool(String name) {
        return new NativeAgentTool() {
            @Override public String name() { return name; }

            @Override public String description() { return "Read bounded rule evidence for a test."; }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"additionalProperties\":true}";
            }

            @Override public String schemaVersion() { return "test-1"; }

            @Override public Set<Role> allowedRoles() { return Set.of(Role.ANSWER); }

            @Override
            public ToolObservation execute(String argumentsJson, ToolScope scope) {
                return ToolObservation.error("UNUSED_TEST_TOOL");
            }
        };
    }

    private RuleEvidenceLookup lookup(RuleEvidenceHit evidence) {
        return (documentVersionId, chunkIds) -> chunkIds.contains(evidence.chunkId())
                ? List.of(evidence)
                : List.of();
    }

    private RuleEvidenceLookup emptyLookup() {
        return (documentVersionId, chunkIds) -> List.of();
    }

    private static final class StubNativeAgent implements NativeToolAgent {
        private final String terminal;
        private final List<ObservationRecord> observations;
        private final int toolCalls;
        private final AtomicInteger runs = new AtomicInteger();
        private RunRequest request;

        private StubNativeAgent(String terminal, List<ObservationRecord> observations, int toolCalls) {
            this.terminal = terminal;
            this.observations = observations;
            this.toolCalls = toolCalls;
        }

        @Override
        public RunResult run(RunRequest request) {
            this.request = request;
            runs.incrementAndGet();
            TerminalValidation validation = request.terminalContract().validator().validate(terminal, observations);
            assertThat(validation.valid()).isTrue();
            return new RunResult(
                    RunStatus.COMPLETED,
                    terminal,
                    "MODEL_COMPLETED",
                    1,
                    toolCalls,
                    observations);
        }
    }
}
