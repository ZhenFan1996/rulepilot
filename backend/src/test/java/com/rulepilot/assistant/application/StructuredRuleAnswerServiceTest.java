package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
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
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

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
        assertThat(reviewed.get().claims()).singleElement()
                .extracting(claim -> claim.citationIds())
                .isEqualTo(List.of(source.chunkId()));
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
    void preservesRetrievedSourcesWhenTheModelAbstainsAfterOneReconsideration() {
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
        assertThat(modelCalls).hasValue(2);
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
                            "DIRECT_RULE",
                            List.of(new CalculationRequest("floor(8 / 3) * 5")));
                });

        StructuredRuleAnswer answer = service(search(source), model).answer(
                "I have 8 resources. How many points do I score?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.answerBasis().name()).isEqualTo("GROUNDED_APPLICATION");
        assertThat(answer.calculations()).singleElement().satisfies(calculation -> {
            assertThat(calculation.expression()).isEqualTo("floor(8 / 3) * 5");
            assertThat(calculation.result()).isEqualTo("10");
        });
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
                "How do I pay the cost before resolving the effect?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.walkthroughSteps()).extracting(step -> step.instruction())
                .containsExactly("Pay the cost.", "Resolve the effect.");
        assertThat(answer.decisionBranches()).isEmpty();
    }

    @Test
    void rejectsASelectedAidThatRemainsMissingAfterOneBoundedRepair() {
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
                "How do I pay the cost before resolving the effect?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void semanticClarificationStopsBeforeRetrievalAndComposition() {
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
                new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification()).contains("What exactly");
        assertThat(retrievalCalled).isFalse();
        assertThat(composeCalls).hasValue(0);
    }

    @Test
    void appliesOneCriticCorrectionThenReviewsTheRevisedAnswer() {
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
                assertThat(feedback).containsExactly("CONTRADICTION: The distance must be one space.");
                return draft(source, "Move one space.", "The cited rule permits one space.");
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

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).isEqualTo("Move one space.");
        assertThat(revisions).hasValue(1);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void withholdsAConclusionWhenTheSemanticCriticIsUnavailable() {
        RuleEvidenceHit source = source("The active player may move one space.");
        GeneratedContentCritic critic = (request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        };

        StructuredRuleAnswer answer = service(
                        search(source),
                        request -> draft(source, "Move one space.", "The cited rule permits it."),
                        critic)
                .answer("How far may I move?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("事实复核");
        assertThat(answer.citations()).isEmpty();
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
        assertThat(retrievalCalls).hasValue(2);
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
        return new ModelDraft(verdict, explanation, List.of(source.chunkId()), List.of(), "HIGH");
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
            return Optional.of(new QuestionInterpretationDraft(
                    request.deterministicType(),
                    ReferenceBinding.CURRENT_QUESTION,
                    List.of(),
                    Set.of(),
                    null,
                    aid,
                    List.of(new PlannedSubquestion(request.question(), needs))));
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
