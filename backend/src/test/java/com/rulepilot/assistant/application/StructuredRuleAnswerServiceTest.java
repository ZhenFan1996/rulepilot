package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandRequest;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandSource;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.application.RuleAnswerRateLimiter.Permit;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

    @Test
    void returnsAnHonestTimeoutOutcomeWhenTheApplicationBudgetStopsTheWorkflow() {
        AssistantRuns runs = mock(AssistantRuns.class);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        RunSnapshot run = new RunSnapshot(
                runId,
                AssistantRunMode.QUESTION_ANSWER,
                versionId,
                "player",
                AssistantRunState.RECEIVED,
                1,
                now,
                now,
                null,
                null);
        when(runs.start(AssistantRunMode.QUESTION_ANSWER, versionId, "player")).thenReturn(run);
        AuditedAgentInvocations stopped = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID ignoredRunId,
                    ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                throw new AgentExecutionStoppedException(StopReason.TIMEOUT);
            }
        };
        StructuredRuleAnswerService service = new StructuredRuleAnswerService(
                understanding,
                (documentVersionId, query, options) -> List.of(),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(),
                request -> { throw new AssertionError("model must not run after timeout"); },
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                runs,
                stopped,
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                null);

        AtomicReference<UUID> streamedRunId = new AtomicReference<>();
        var creation = service.answerWithRun(
                "When does this resolve?",
                new QuestionContext(versionId),
                "player",
                null,
                streamedRunId::set);

        assertThat(creation.answer().status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(streamedRunId).hasValue(runId);
        verify(runs).fail(eq(runId), eq(1L), eq("AGENT_TIMEOUT"), any());
    }

    @Test
    void publishesOneDirectAnswerWithOnlyCurrentVersionCitationsAndSemanticReview() {
        RuleEvidenceHit source = source("The active player may move one space.");
        AtomicReference<ModelRequest> composed = new AtomicReference<>();
        AtomicReference<GeneratedContentCritic.ReviewRequest> reviewed = new AtomicReference<>();
        RuleAnswerModel model = request -> {
            composed.set(request);
            return draft(source, "You may move one space.", "The cited rule grants that move.");
        };
        GeneratedContentCritic critic = (request, risk) -> {
            reviewed.set(request);
            return new Review(true, List.of());
        };

        StructuredRuleAnswer answer = service(search(source), model, critic).answer(
                "How far may the active player move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("You may move one space.");
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(source.chunkId());
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.excerpt()).isEqualTo(source.excerpt());
        });
        assertThat(composed.get().answerAid()).isEqualTo(AnswerAid.NONE);
        assertThat(composed.get().evidence()).singleElement()
                .extracting(input -> input.chunkId())
                .isEqualTo(source.chunkId());
        assertThat(reviewed.get().claims()).hasSize(2).allSatisfy(claim ->
                assertThat(claim.citationIds()).containsExactly(source.chunkId()));
    }

    @Test
    void standaloneQuestionsDoNotForceASeparateInterpretationStage() {
        RuleEvidenceHit source = source("After taking tiles, move every remaining tile to the center.");
        AtomicInteger interpretations = new AtomicInteger();
        AtomicBoolean composed = new AtomicBoolean();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                composed.set(true);
                return draft(source, "Move them to the center.", "The remaining tiles go to the center.");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                interpretations.incrementAndGet();
                throw new AssertionError(
                        "a successful current-question retrieval must not force context recovery");
            }
        };

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "I took one color from a display. Where do the remaining tiles go?",
                new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(RuleCitation::chunkId).containsExactly(source.chunkId());
        assertThat(interpretations).hasValue(0);
        assertThat(composed).isTrue();
    }

    @Test
    void anOrdinarySecondQuestionUsesItsOwnRetrievedEvidenceWithoutInterpretation() {
        RuleEvidenceHit source = source("The remaining tiles move to the center.");
        AtomicBoolean interpreted = new AtomicBoolean();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return draft(source, "Move them to the center.", "The current question retrieved this rule directly.");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                interpreted.set(true);
                return Optional.empty();
            }
        };

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "Where do the remaining tiles go?",
                new QuestionContext(versionId, "Which color did I take?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(interpreted).isFalse();
    }

    @Test
    void explicitLearningIntentSelectsItsAidWithoutInterpretation() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        AtomicBoolean interpreted = new AtomicBoolean();
        AtomicReference<AnswerAid> composedAid = new AtomicReference<>();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                composedAid.set(request.answerAid());
                return draft(source, "Pay first.", "The cited sequence can be explained step by step.");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                interpreted.set(true);
                return Optional.empty();
            }
        };

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "Why must I pay before resolving?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(composedAid).hasValue(AnswerAid.WALKTHROUGH);
        assertThat(interpreted).isFalse();
    }

    @Test
    void preservesACompleteSupportedAnswerAndLocalizesOnlyTheMissingSubquestion() {
        RuleEvidenceHit source = source("The game ends immediately when a player reaches twenty points.");
        String explanation = "Reach twenty points to end the game immediately. "
                + "The currently available rule excerpt cannot confirm a guaranteed opening; "
                + "which role and opening phase do you want advice for?";

        StructuredRuleAnswer answer = service(
                        search(source),
                        request -> draft(source, "Reach twenty points to win.", explanation))
                .answer(
                        "How do I win, and what opening guarantees victory?",
                        new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("Reach twenty points to win.");
        assertThat(answer.explanation()).isEqualTo(explanation);
        assertThat(answer.citations()).extracting(RuleCitation::chunkId)
                .containsExactly(source.chunkId());
    }

    @Test
    void publishesSupportedCompoundEvidenceWhenCompleteListCoverageRemainsUnmet() {
        RuleEvidenceHit source = source("Reaching twenty points is one way to win.");
        AtomicReference<ModelRequest> composed = new AtomicReference<>();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                composed.set(request);
                return draft(
                        source,
                        "Reaching twenty points is a confirmed victory condition.",
                        "The cited rule supports that condition, but the available evidence does not establish a complete list of every victory condition.");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                return Optional.of(new QuestionInterpretationDraft(
                        QuestionType.RULE_QUERY,
                        ReferenceBinding.CURRENT_QUESTION,
                        List.of("victory condition"),
                        Set.of(),
                        null,
                        AnswerAid.NONE,
                        List.of(
                                new PlannedSubquestion(
                                        "How does a player win?", Set.of(EvidenceNeed.DIRECT_RULE)),
                                new PlannedSubquestion(
                                        "What are all victory conditions?", Set.of(EvidenceNeed.COMPLETE_LIST)))));
            }
        };
        AtomicBoolean firstSearch = new AtomicBoolean(true);
        AnswerEvidenceRefiner preservePartial =
                (runId, question, context, username, sessionId, deterministic) -> deterministic;

        StructuredRuleAnswer answer = service(
                        (version, query, options) -> firstSearch.getAndSet(false)
                                ? List.of()
                                : List.of(hit(source)),
                        model,
                        new InMemoryAnswerCache(),
                        new RecordingRateLimiter(),
                        noConfirmedRulings(),
                        acceptedCritic(),
                        preservePartial)
                .answer(
                        "How do I win, and what are all victory conditions?",
                        new QuestionContext(versionId, "What counts as victory?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("does not establish a complete list");
        assertThat(answer.citations()).extracting(RuleCitation::chunkId).containsExactly(source.chunkId());
        assertThat(composed.get().evidenceNeeds())
                .containsExactlyInAnyOrder(EvidenceNeed.DIRECT_RULE, EvidenceNeed.COMPLETE_LIST);
    }

    @Test
    void stopsBeforeCompositionWhenNoEvidenceIsRetrieved() {
        AtomicInteger modelCalls = new AtomicInteger();
        RuleAnswerModel model = request -> {
            modelCalls.incrementAndGet();
            return null;
        };

        StructuredRuleAnswer answer = service((version, query, options) -> List.of(), model).answer(
                "What is the rule?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    void reportsRetrievalUnavailableWhenEveryCoreSearchFails() {
        RuleAnswerModel model = request -> {
            throw new AssertionError("retrieval failure must stop before composition");
        };

        StructuredRuleAnswer answer = service((version, query, options) -> {
            throw new IllegalStateException("search unavailable");
        }, model).answer("What is the rule?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("检索暂时不可用");
    }

    @Test
    void preservesRetrievedSourcesWhenTheModelAbstainsWithoutRegeneratingItsDecision() {
        RuleEvidenceHit source = source("Each coin scores one point.");
        AtomicInteger modelCalls = new AtomicInteger();
        RuleAnswerModel model = request -> {
            modelCalls.incrementAndGet();
            return new ModelDraft(false, "uncertain", null, null, List.of(), List.of(), "LOW");
        };

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId())
                .containsExactly(source.chunkId());
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    void recomputesArithmeticOnlyWhenTheAcceptedPlanSelectsCalculation() {
        RuleEvidenceHit source = source("Each complete set of 3 resources scores 5 points.");
        PlanningModel model = planningModel(
                AnswerAid.CALCULATION,
                Set.of(EvidenceNeed.DIRECT_RULE),
                request -> {
                    assertThat(request.answerAid()).isEqualTo(AnswerAid.CALCULATION);
                    return new ModelDraft(
                            true,
                            null,
                            "You score 10 points.",
                            "The recomputed total is 10 points.",
                            List.of(source.chunkId()),
                            List.of(),
                            "HIGH",
                            "GROUNDED_APPLICATION",
                            List.of(new CalculationRequest(
                                    "floor(8 / 3) * 5",
                                    new BigDecimal("10"),
                                    "points",
                                    List.of(
                                            new CalculationOperandRequest(
                                                    "available resources",
                                                    new BigDecimal("8"),
                                                    CalculationOperandSource.QUESTION,
                                                    "8 resources",
                                                    null),
                                            new CalculationOperandRequest(
                                                    "resources per set",
                                                    new BigDecimal("3"),
                                                    CalculationOperandSource.EVIDENCE,
                                                    "set of 3 resources",
                                                    source.chunkId()),
                                            new CalculationOperandRequest(
                                                    "points per set",
                                                    new BigDecimal("5"),
                                                    CalculationOperandSource.EVIDENCE,
                                                    "5 points",
                                                    source.chunkId())))));
                });

        AtomicInteger searches = new AtomicInteger();
        StructuredRuleAnswer answer = service((version, query, options) ->
                        searches.getAndIncrement() == 0 ? List.of() : List.of(hit(source)), model)
                .answer(
                "I have 8 resources. How many points do I score?",
                new QuestionContext(versionId, "How are complete resource sets scored?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.answerBasis().name()).isEqualTo("GROUNDED_APPLICATION");
        assertThat(answer.calculations()).singleElement().satisfies(calculation -> {
            assertThat(calculation.expression()).isEqualTo("floor(8 / 3) * 5");
            assertThat(calculation.result()).isEqualTo("10");
        });
        assertThat(model.interpreted()).isTrue();
    }

    @Test
    void publishesTheSingleStructuredAidChosenBySemanticPlanning() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        PlanningModel model = planningModel(
                AnswerAid.WALKTHROUGH,
                Set.of(EvidenceNeed.SEQUENCE),
                request -> new ModelDraft(
                        true,
                        null,
                        "Pay first, then resolve.",
                        "Use the cited order.",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "DIRECT_RULE",
                        List.of(),
                        List.of(),
                        List.of(
                                new WalkthroughStepRequest(
                                        "Pay the cost.", "This happens first.", "RULE_ORDER", List.of(source.chunkId())),
                                new WalkthroughStepRequest(
                                        "Resolve the effect.", "This follows payment.", "RULE_ORDER", List.of(source.chunkId())))));

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How do I pay the cost before resolving the effect?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.walkthroughSteps()).extracting(step -> step.instruction())
                .containsExactly("Pay the cost.", "Resolve the effect.");
        assertThat(answer.decisionBranches()).isEmpty();
    }

    @Test
    void publishesTheValidatedCoreWhenASelectedPresentationAidIsMissing() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        AtomicInteger revisions = new AtomicInteger();
        PlanningModel model = planningModel(
                AnswerAid.WALKTHROUGH,
                Set.of(EvidenceNeed.SEQUENCE),
                request -> draft(source, "Pay first.", "Then resolve."),
                (request, previous, feedback) -> {
                    revisions.incrementAndGet();
                    assertThat(feedback).anySatisfy(value -> assertThat(value).contains("walkthroughSteps"));
                    return previous;
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How do I pay the cost before resolving the effect?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("Pay first.");
        assertThat(answer.explanation()).isEqualTo("Then resolve.");
        assertThat(answer.walkthroughSteps()).isEmpty();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void rejectsAMalformedSelectedPresentationAidInsteadOfPublishingOnlyItsProse() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        AtomicInteger revisions = new AtomicInteger();
        String verdict = "Pay first, then resolve.";
        String explanation = "The cited rule puts payment before the effect.";
        PlanningModel model = planningModel(
                AnswerAid.WALKTHROUGH,
                Set.of(EvidenceNeed.SEQUENCE),
                request -> new ModelDraft(
                        true,
                        null,
                        verdict,
                        explanation,
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "DIRECT_RULE",
                        List.of(),
                        List.of(),
                        List.of(new WalkthroughStepRequest(
                                "Pay.", "Resolve later.", "NOT_AN_ORDER", List.of(source.chunkId())))),
                (request, previous, feedback) -> {
                    revisions.incrementAndGet();
                    return previous;
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How do I pay the cost before resolving the effect?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("结构");
        assertThat(answer.shortVerdict()).doesNotContain(verdict);
        assertThat(answer.explanation()).contains("结构").doesNotContain(explanation);
        assertThat(answer.walkthroughSteps()).isEmpty();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void preservesNaturalTechnicalTermsInASelectedOptionalAid() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        AtomicInteger revisions = new AtomicInteger();
        String verdict = "Pay first, then resolve.";
        String explanation = "The cited rule puts payment before the effect.";
        PlanningModel model = planningModel(
                AnswerAid.WALKTHROUGH,
                Set.of(EvidenceNeed.SEQUENCE),
                request -> new ModelDraft(
                        true,
                        null,
                        verdict,
                        explanation,
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "DIRECT_RULE",
                        List.of(),
                        List.of(),
                        List.of(new WalkthroughStepRequest(
                                "Pay first.",
                                "Then resolve according to internal citationIds.",
                                "RULE_ORDER",
                                List.of(source.chunkId())))),
                (request, previous, feedback) -> {
                    revisions.incrementAndGet();
                    return previous;
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How do I pay the cost before resolving the effect?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo(verdict);
        assertThat(answer.explanation()).isEqualTo(explanation);
        assertThat(answer.walkthroughSteps()).singleElement().satisfies(step -> {
            assertThat(step.instruction()).isEqualTo("Pay first.");
            assertThat(step.explanation()).isEqualTo("Then resolve according to internal citationIds.");
        });
        assertThat(revisions).hasValue(0);
    }

    @Test
    void rejectsAnUnselectedMalformedAidInsteadOfSilentlyDeletingIt() {
        RuleEvidenceHit source = source("The active player may move one space.");
        AtomicInteger revisions = new AtomicInteger();
        String explanation = "The cited rule grants one move and states the limit directly.";
        PlanningModel model = planningModel(
                AnswerAid.NONE,
                Set.of(EvidenceNeed.DIRECT_RULE),
                request -> new ModelDraft(
                        true,
                        null,
                        "You may move one space.",
                        explanation,
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "DIRECT_RULE",
                        List.of(),
                        List.of(),
                        List.of(new WalkthroughStepRequest(
                                "internal chunkId marker", "", "NOT_AN_ORDER", List.of(UUID.randomUUID()))),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                (request, previous, feedback) -> {
                    revisions.incrementAndGet();
                    return previous;
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How far may the active player move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("结构");
        assertThat(answer.shortVerdict()).doesNotContain("You may move one space.");
        assertThat(answer.explanation()).contains("结构").doesNotContain(explanation);
        assertThat(answer.walkthroughSteps()).isEmpty();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void publishesNaturalTechnicalTermsWithoutSpendingARepairCall() {
        RuleEvidenceHit source = source("Pay the cost before resolving the effect.");
        AtomicInteger revisions = new AtomicInteger();
        PlanningModel model = planningModel(
                AnswerAid.WALKTHROUGH,
                Set.of(EvidenceNeed.SEQUENCE),
                request -> draft(source, "Pay first.", "The internal chunkId says to resolve next."),
                (request, previous, feedback) -> {
                    revisions.incrementAndGet();
                    return draft(source, "Pay first.", "The cited rule says to resolve next.");
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "How do I pay the cost before resolving the effect?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("Pay first.");
        assertThat(answer.explanation()).isEqualTo("The internal chunkId says to resolve next.");
        assertThat(answer.walkthroughSteps()).isEmpty();
        assertThat(revisions).hasValue(0);
    }

    @Test
    void emptyCurrentQuestionEvidenceMayRecoverIntoAClarificationBeforeComposition() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        AtomicInteger composeCalls = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                composeCalls.incrementAndGet();
                return null;
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                return Optional.of(new QuestionInterpretationDraft(
                        QuestionType.SITUATION_QUERY,
                        ReferenceBinding.NEEDS_CLARIFICATION,
                        List.of(),
                        Set.of(MissingQuestionContext.REFERENCED_OBJECT),
                        null,
                        AnswerAid.NONE,
                        List.of()));
            }
        };
        StructuredRuleAnswerService service = service((version, query, options) -> {
            retrievalCalled.set(true);
            return List.of();
        }, model);

        StructuredRuleAnswer answer = service.answer(
                "When does this trigger?",
                new QuestionContext(versionId, "What happens after movement?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification()).contains("What exactly");
        assertThat(retrievalCalled).isTrue();
        assertThat(composeCalls).hasValue(0);
    }

    @Test
    void invalidRecoveryInterpretationPreservesTheCurrentQuestionInsufficiency() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        AtomicInteger composeCalls = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                composeCalls.incrementAndGet();
                return null;
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                return Optional.empty();
            }
        };
        StructuredRuleAnswer answer = service((version, query, options) -> {
            retrievalCalled.set(true);
            return List.of();
        }, model).answer(
                "When does this resolve?",
                new QuestionContext(versionId, "What happens after movement?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(retrievalCalled).isTrue();
        assertThat(composeCalls).hasValue(0);
    }

    @Test
    void recoveryInterpretationTimeoutPreservesTheCurrentQuestionInsufficiency() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                throw new AssertionError("composition must not run after interpretation timeout");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                throw new RuleAnswerModelTimeoutException("timed out", new java.util.concurrent.TimeoutException());
            }
        };
        StructuredRuleAnswer answer = service((version, query, options) -> {
            retrievalCalled.set(true);
            return List.of();
        }, model).answer(
                "When does this resolve?",
                new QuestionContext(versionId, "What happens after movement?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(retrievalCalled).isTrue();
    }

    @Test
    void recoveryInterpretationCannotSwallowAWorkflowBudgetStop() {
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                throw new AssertionError("a stopped workflow must not continue to composition");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
            }
        };

        assertThatThrownBy(() -> service((version, query, options) -> List.of(), model)
                        .answer(
                                "When does this resolve?",
                                new QuestionContext(
                                        versionId,
                                        "What happens after movement?",
                                        null,
                                        PlayerLocale.EN)))
                .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                        stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.MODEL_BUDGET));
    }

    @Test
    void failedRecoveryInterpretationDoesNotBlockLaterVerifiedEvidence() {
        RuleEvidenceHit source = source("The effect resolves after movement.");
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return draft(source, "Resolve it after movement.", "The verified rule supplies the timing.");
            }

            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                return Optional.empty();
            }
        };
        AnswerEvidenceRefiner refiner = (runId, question, context, username, sessionId, deterministic) ->
                new AnswerEvidenceRetriever.Result(List.of(hit(source)), AnswerEvidenceRetriever.State.READY);

        StructuredRuleAnswer answer = service(
                        (version, query, options) -> List.of(),
                        model,
                        new InMemoryAnswerCache(),
                        new RecordingRateLimiter(),
                        noConfirmedRulings(),
                        acceptedCritic(),
                        refiner)
                .answer(
                        "When does this resolve?",
                        new QuestionContext(versionId, "What happens after movement?", null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(RuleCitation::chunkId).containsExactly(source.chunkId());
    }

    @Test
    void reportsOneCriticFindingWithoutRevisingOrErasingTheValidatedAnswer() {
        RuleEvidenceHit source = source("The active player may move one space.");
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return draft(source, "Move two spaces.", "The draft overstates the distance.");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                throw new AssertionError("the evaluation-only critic must not reopen answer composition");
            }
        };
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> reviews.getAndIncrement() == 0
                ? new Review(true, List.of(new Issue(
                        IssueType.CONTRADICTION,
                        1,
                        List.of(source.chunkId()),
                        "The distance must be one space.")))
                : new Review(true, List.of());

        StructuredRuleAnswer answer = service(search(source), model, critic).answer(
                "How far may the active player move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(answer.shortVerdict()).isEqualTo("Move two spaces.");
        assertThat(answer.explanation()).isEqualTo("The draft overstates the distance.");
        assertThat(answer.warnings())
                .extracting(AnswerWarning::type)
                .containsExactly(AnswerWarning.Type.REVIEW_UNRESOLVED);
        assertThat(revisions).hasValue(0);
        assertThat(reviews).hasValue(1);
    }

    @Test
    void preservesAValidatedConclusionWhenTheOptionalSemanticCriticIsUnavailable() {
        RuleEvidenceHit source = source("The active player may move one space.");
        GeneratedContentCritic critic = (request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        };

        StructuredRuleAnswer answer = service(
                        search(source),
                        request -> draft(source, "Move one space.", "The cited rule permits it."),
                        critic)
                .answer("How far may I move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(answer.shortVerdict()).isEqualTo("Move one space.");
        assertThat(answer.citations()).extracting(RuleCitation::chunkId)
                .containsExactly(source.chunkId());
    }

    @Test
    void keepsAnUnsupportedCriticFindingDiagnosticAfterDeterministicPublication() {
        RuleEvidenceHit source = source("An opaque descriptive panel names the cobalt spindle.");
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return draft(source, "The spindle returns now.", "The panel supposedly establishes the return rule.");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return previousDraft;
            }
        };
        GeneratedContentCritic critic = (request, risk) -> new Review(true, List.of(new Issue(
                IssueType.UNSUPPORTED_CLAIM,
                1,
                List.of(source.chunkId()),
                "The cited panel does not state the claimed return rule.")));

        StructuredRuleAnswer answer = service(search(source), model, critic).answer(
                "Does the cobalt spindle return now?",
                new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(answer.shortVerdict()).isEqualTo("The spindle returns now.");
        assertThat(answer.citations()).extracting(citation -> citation.chunkId())
                .containsExactly(source.chunkId());
        assertThat(answer.warnings())
                .extracting(AnswerWarning::type)
                .containsExactly(AnswerWarning.Type.REVIEW_UNRESOLVED);
    }

    @Test
    void servesAValidatedCacheHitWithoutRepeatingRetrievalCompositionOrReview() {
        RuleEvidenceHit source = source("Each coin scores one point.");
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger criticCalls = new AtomicInteger();
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        StructuredRuleAnswerService service = service(
                (version, query, options) -> {
                    retrievalCalls.incrementAndGet();
                    return List.of(hit(source));
                },
                request -> {
                    modelCalls.incrementAndGet();
                    return draft(source, "One point per coin.", "Each coin contributes one point.");
                },
                cache,
                new RecordingRateLimiter(),
                noConfirmedRulings(),
                (request, risk) -> {
                    criticCalls.incrementAndGet();
                    return new Review(true, List.of());
                },
                null);

        StructuredRuleAnswer first = service.answer("How are coins scored?", new QuestionContext(versionId));
        StructuredRuleAnswer second = service.answer("How are coins scored?", new QuestionContext(versionId));

        assertThat(second).isEqualTo(first);
        assertThat(modelCalls).hasValue(1);
        assertThat(criticCalls).hasValue(1);
        assertThat(retrievalCalls).hasValue(1);
    }

    @Test
    void returnsAnOwnedConfirmedRulingBeforeRateLimitCacheRetrievalAndModel() {
        RuleEvidenceHit source = source("Each coin scores one point.");
        UUID rulingId = UUID.randomUUID();
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        ConfirmedRulingLookup confirmed = (documentVersionId, expansionIds, question, username) -> {
            assertThat(question).isEqualTo("How are coins scored?");
            assertThat(username).isEqualTo("alice");
            return Optional.of(new ConfirmedRulingLookup.ConfirmedAnswer(
                    rulingId,
                    versionId,
                    "Use the confirmed score.",
                    "Each coin scores one point.",
                    List.of(new ConfirmedRulingLookup.Citation(
                            source.chunkId(), versionId, source.sectionType(), source.heading(), source.excerpt(), 8, 8)),
                    List.of(),
                    "HIGH",
                    true,
                    4));
        };
        StructuredRuleAnswerService service = service(
                (version, query, options) -> {
                    downstreamCalled.set(true);
                    return List.of();
                },
                request -> {
                    downstreamCalled.set(true);
                    return null;
                },
                new InMemoryAnswerCache(),
                limiter,
                confirmed,
                acceptedCritic(),
                null);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId), "alice", null);

        assertThat(answer.shortVerdict()).isEqualTo("Use the confirmed score.");
        assertThat(answer.confirmedRulingId()).isEqualTo(rulingId);
        assertThat(answer.confirmedRulingVersion()).isEqualTo(4);
        assertThat(answer.official()).isTrue();
        assertThat(downstreamCalled).isFalse();
        assertThat(limiter.userChecks).isZero();
    }

    @Test
    void usesRefinedEvidenceAsTheOnlyCompositionAndPublicationScope() {
        RuleEvidenceHit initial = source("Generic overview.");
        RuleEvidenceHit refined = source("The exact rule permits one move.");
        AnswerEvidenceRefiner refiner = (runId, question, context, username, sessionId, deterministic) ->
                new AnswerEvidenceRetriever.Result(List.of(hit(refined)), AnswerEvidenceRetriever.State.READY);
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(input -> input.chunkId())
                    .containsExactly(refined.chunkId());
            return draft(refined, "One move is allowed.", "The exact cited rule permits it.");
        };
        StructuredRuleAnswerService service = service(
                search(initial),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                noConfirmedRulings(),
                acceptedCritic(),
                refiner);

        StructuredRuleAnswer answer = service.answer("May I move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId())
                .containsExactly(refined.chunkId());
    }

    @Test
    void mapsModelTimeoutAndRateLimitStorageFailureWithoutStartingUnsafeDownstreamWork() {
        RuleEvidenceHit source = source("Each coin scores one point.");
        StructuredRuleAnswer timeout = service(
                        search(source),
                        request -> {
                            throw new RuleAnswerModelTimeoutException("timeout", new IllegalStateException());
                        })
                .answer("How are coins scored?", new QuestionContext(versionId));
        assertThat(timeout.status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);

        AtomicBoolean retrievalCalled = new AtomicBoolean();
        RuleAnswerRateLimiter unavailable = new RuleAnswerRateLimiter() {
            @Override
            public void checkUser(String username) {
                throw new RuleAnswerRateLimitUnavailableException(5, new IllegalStateException("store unavailable"));
            }

            @Override
            public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                throw new AssertionError("model permit must not be acquired");
            }
        };
        StructuredRuleAnswerService service = service(
                (version, query, options) -> {
                    retrievalCalled.set(true);
                    return List.of();
                },
                request -> null,
                new InMemoryAnswerCache(),
                unavailable,
                noConfirmedRulings(),
                acceptedCritic(),
                null);

        assertThatThrownBy(() -> service.answer("How are coins scored?", new QuestionContext(versionId)))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class);
        assertThat(retrievalCalled).isFalse();
    }

    private StructuredRuleAnswerService service(HybridRuleSearch retrieval, RuleAnswerModel model) {
        return service(retrieval, model, acceptedCritic());
    }

    private StructuredRuleAnswerService service(
            HybridRuleSearch retrieval, RuleAnswerModel model, GeneratedContentCritic critic) {
        return service(
                retrieval,
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                noConfirmedRulings(),
                critic,
                null);
    }

    private StructuredRuleAnswerService service(
            HybridRuleSearch retrieval,
            RuleAnswerModel model,
            RuleAnswerCache cache,
            RuleAnswerRateLimiter limiter,
            ConfirmedRulingLookup confirmed,
            GeneratedContentCritic critic,
            AnswerEvidenceRefiner refiner) {
        RuleEvidenceLookup evidenceLookup = (documentVersionId, chunkIds) -> List.of();
        return new StructuredRuleAnswerService(
                understanding,
                retrieval,
                VisualRulebookPageFactSearch.empty(),
                evidenceLookup,
                model,
                cache,
                limiter,
                new MutableRuleDataVersion(),
                confirmed,
                new PolicyEvidenceVerifier(),
                critic,
                null,
                new ImmediateAuditedAgentInvocations(),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                refiner);
    }

    private PlanningModel planningModel(
            AnswerAid aid,
            Set<EvidenceNeed> needs,
            Function<ModelRequest, ModelDraft> composer) {
        return planningModel(aid, needs, composer, (request, previous, feedback) -> composer.apply(request));
    }

    private PlanningModel planningModel(
            AnswerAid aid,
            Set<EvidenceNeed> needs,
            Function<ModelRequest, ModelDraft> composer,
            Revision revision) {
        return new PlanningModel(aid, needs, composer, revision);
    }

    private ModelDraft draft(RuleEvidenceHit source, String verdict, String explanation) {
        return new ModelDraft(
                true,
                null,
                verdict,
                explanation,
                List.of(source.chunkId()),
                List.of(),
                "HIGH",
                "DIRECT_RULE");
    }

    private HybridRuleSearch search(RuleEvidenceHit source) {
        return (version, query, options) -> List.of(hit(source));
    }

    private HybridEvidenceHit hit(RuleEvidenceHit source) {
        return new HybridEvidenceHit(source, source.score(), 1, null, false);
    }

    private RuleEvidenceHit source(String excerpt) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "RULE", "Rule", excerpt, 8, 8, 0.9);
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new Review(false, List.of());
    }

    private ConfirmedRulingLookup noConfirmedRulings() {
        return (documentVersionId, expansionIds, question, username) -> Optional.empty();
    }

    @FunctionalInterface
    private interface Revision {
        ModelDraft apply(ModelRequest request, ModelDraft previous, List<String> feedback);
    }

    private static final class PlanningModel implements RuleAnswerModel {
        private final AnswerAid aid;
        private final Set<EvidenceNeed> needs;
        private final Function<ModelRequest, ModelDraft> composer;
        private final Revision revision;
        private boolean interpreted;

        private PlanningModel(
                AnswerAid aid,
                Set<EvidenceNeed> needs,
                Function<ModelRequest, ModelDraft> composer,
                Revision revision) {
            this.aid = aid;
            this.needs = Set.copyOf(needs);
            this.composer = composer;
            this.revision = revision;
        }

        @Override
        public ModelDraft compose(ModelRequest request) {
            return composer.apply(request);
        }

        @Override
        public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
            return revision.apply(request, previousDraft, feedback);
        }

        @Override
        public boolean supportsQuestionInterpretation() {
            return true;
        }

        @Override
        public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
            interpreted = true;
            return Optional.of(new QuestionInterpretationDraft(
                    request.deterministicType(),
                    ReferenceBinding.CURRENT_QUESTION,
                    List.of(),
                    Set.of(),
                    null,
                    aid,
                    List.of(new PlannedSubquestion(request.question(), needs))));
        }

        private boolean interpreted() {
            return interpreted;
        }
    }

    private static final class InMemoryAnswerCache implements RuleAnswerCache {
        private final Map<AnswerCacheKey, StructuredRuleAnswer> values = new HashMap<>();

        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            values.put(key, answer);
        }
    }

    private static final class RecordingRateLimiter implements RuleAnswerRateLimiter {
        private int userChecks;
        private int modelAcquires;
        private int releases;

        @Override
        public void checkUser(String username) {
            userChecks++;
        }

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            modelAcquires++;
            return () -> releases++;
        }
    }

    private static final class MutableRuleDataVersion implements RuleDataVersion {
        private long value = 1;

        @Override
        public long current(UUID documentVersionId) {
            return value;
        }

        @Override
        public long increment(UUID documentVersionId) {
            return ++value;
        }
    }
}
