package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

    @Test
    void returnsOnlyValidatedCitationsFromCurrentVersion() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, 1, false)),
                request -> new ModelDraft(
                        "Coins score one point.", "Each coin contributes one point.",
                        List.of(source.chunkId()), List.of("Only count remaining coins."), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(source.chunkId());
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(answer.official()).isFalse();
    }

    @Test
    void passesUnderstoodGameplayContextToTheAnswerModel() {
        RuleEvidenceHit source = evidence("ACTIONS");
        UUID expansionId = UUID.randomUUID();
        AtomicReference<RuleAnswerModel.ModelRequest> captured = new AtomicReference<>();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    captured.set(request);
                    return new ModelDraft(
                            "可以执行。", "在行动阶段支付规则所列费用后执行。",
                            List.of(source.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of(expansionId)));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(captured.get().questionType()).isEqualTo(com.rulepilot.assistant.domain.QuestionType.SITUATION_QUERY);
        assertThat(captured.get().context().currentLessonSection()).isEqualTo("ACTIONS");
        assertThat(captured.get().context().gamePhase()).isEqualTo("ACTION_PHASE");
        assertThat(captured.get().context().playerCount()).isEqualTo(4);
        assertThat(captured.get().context().activeExpansionCount()).isEqualTo(1);
    }

    @Test
    void honorsModelAbstentionWithoutPublishingGeneratedClaims() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        false,
                        "The evidence describes coins but not the requested bonus.",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        "LOW"));

        var answer = service.answer(
                "How is the hidden bonus scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("未能直接回答");
        assertThat(answer.shortVerdict()).doesNotContain("hidden bonus", "coins");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void rejectsCitationThatWasNotRetrieved() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft("Unsupported", "Unsupported", List.of(UUID.randomUUID()), List.of(), "HIGH"));

        var answer = service.answer(
                "How is scoring resolved?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void missingContextStopsBeforeRetrievalAndModel() {
        AtomicBoolean called = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> {
                    called.set(true);
                    return List.of();
                },
                request -> {
                    called.set(true);
                    return null;
                });

        var answer = service.answer(
                "Can I play this card from my hand?",
                new QuestionContext(versionId, null, null, 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification()).contains("GAME_PHASE", "SITUATION_DETAILS");
        assertThat(called).isFalse();
    }

    @Test
    void refusesWhenNoEvidenceWasRetrieved() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "What does this unknown symbol do?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void answersFromContextualSupplementaryRetrievalWhenPrimaryHasNoMatch() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        assertThat(options.sectionTypes()).isEmpty();
                        return List.of();
                    }
                    assertThat(query).contains("legal action", "ACTION PHASE", "4 players");
                    assertThat(options.sectionTypes()).contains("ACTIONS");
                    return List.of(new HybridEvidenceHit(source, 0.03, 1, null, true));
                },
                request -> new ModelDraft(
                        "可以执行。", "行动阶段允许执行该行动。",
                        List.of(source.chunkId()), List.of(), "MEDIUM"));

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void keepsPrimaryEvidenceWhenSupplementaryRetrievalFails() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        return List.of(new HybridEvidenceHit(source, 0.03, 1, null, false));
                    }
                    throw new IllegalStateException("supplementary retrieval unavailable");
                },
                request -> new ModelDraft(
                        "每枚硬币一分。", "计算最终分数时，每枚硬币计一分。",
                        List.of(source.chunkId()), List.of(), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void reportsModelTimeoutWithoutLeakingAnswerContent() {
        RuleEvidenceHit source = evidence("ACTIONS");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    throw new RuleAnswerModelTimeoutException("provider details", new RuntimeException("secret"));
                });

        var answer = service.answer(
                "Which actions are available during a turn?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(answer.shortVerdict()).doesNotContain("provider details", "secret");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void rejectsVersionConflictBeforeCallingModel() {
        UUID otherVersion = UUID.randomUUID();
        RuleEvidenceHit wrongVersion = new RuleEvidenceHit(
                UUID.randomUUID(), otherVersion, "SCORING", "Scoring", "One point.", 2, 2, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(wrongVersion, 0.03, 1, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.VERSION_CONFLICT);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void refusesConflictingSnapshotsBeforeCallingModel() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit first = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores one point.", 8, 8, 0.8);
        RuleEvidenceHit conflicting = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores two points.", 8, 8, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(
                        new HybridEvidenceHit(first, 0.03, 1, null, false),
                        new HybridEvidenceHit(conflicting, 0.02, 2, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("冲突");
        assertThat(modelCalled).isFalse();
    }

    @Test
    void blocksLowConfidenceAnswerRejectedByCritic() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic rejectingCritic = (request, risk) -> {
            criticCalls.incrementAndGet();
            assertThat(request.taskContext().objective()).contains("how does scoring work?");
            assertThat(request.taskContext().requiredCoverage()).contains("RULE_QUERY", "player count not provided");
            return new GeneratedContentCritic.Review(true, List.of(new Issue(
                    IssueType.OVERREACH, 1, List.of(source.chunkId()), "The conclusion exceeds the evidence.")));
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "Coins always decide the winner.", "Coins decide every tie.",
                        List.of(source.chunkId()), List.of(), "LOW"),
                rejectingCritic);

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("一致性审查");
        assertThat(answer.citations()).isEmpty();
        assertThat(criticCalls).hasValue(1);
    }

    @Test
    void returnsOwnedConfirmedRulingBeforeCacheRetrievalAndModel() {
        UUID rulingId = UUID.randomUUID();
        UUID expansionId = UUID.randomUUID();
        RuleEvidenceHit source = evidence("SCORING");
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfirmedRulingLookup lookup = (documentVersionId, expansionIds, question, username) -> {
            assertThat(documentVersionId).isEqualTo(versionId);
            assertThat(expansionIds).containsExactly(expansionId);
            assertThat(question).isEqualTo("how are coins scored?");
            assertThat(username).isEqualTo("alice");
            return Optional.of(new ConfirmedRulingLookup.ConfirmedAnswer(
                    rulingId,
                    versionId,
                    "Use the confirmed score.",
                    "Each remaining coin scores one point.",
                    List.of(new ConfirmedRulingLookup.Citation(
                            source.chunkId(), versionId, source.sectionType(), source.heading(), source.excerpt(), 8, 8)),
                    List.of(),
                    "HIGH",
                    false,
                    4));
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    downstreamCalled.set(true);
                    return List.of();
                },
                request -> {
                    downstreamCalled.set(true);
                    return null;
                },
                new InMemoryAnswerCache(),
                rateLimiter,
                new MutableRuleDataVersion(),
                lookup,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?",
                new QuestionContext(versionId, "SCORING", null, 3, Set.of(expansionId)),
                "alice",
                null);

        assertThat(answer.shortVerdict()).isEqualTo("Use the confirmed score.");
        assertThat(answer.confirmedRulingId()).isEqualTo(rulingId);
        assertThat(answer.confirmedRulingVersion()).isEqualTo(4);
        assertThat(downstreamCalled).isFalse();
        assertThat(rateLimiter.userChecks).isZero();
        assertThat(metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling").count())
                .isEqualTo(1);
    }

    @Test
    void naturallyMissesOldCacheEntryAfterRuleDataVersionChanges() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        MutableRuleDataVersion versions = new MutableRuleDataVersion();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                cache,
                rateLimiter,
                versions,
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);
        QuestionContext context = new QuestionContext(versionId, "SCORING", null, 3, Set.of());

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);
        versions.increment(versionId);
        StructuredRuleAnswer afterRuleChange = service.answer("How are coins scored?", context);

        assertThat(second).isEqualTo(first);
        assertThat(afterRuleChange).isEqualTo(first);
        assertThat(modelCalls).hasValue(2);
        assertThat(rateLimiter.userChecks).isEqualTo(3);
        assertThat(rateLimiter.modelAcquires).isEqualTo(2);
        assertThat(rateLimiter.releases).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "miss").count()).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "hit").count()).isEqualTo(1);
    }

    @Test
    void retrievesAndReturnsValidatedAnswerWhenCacheIsUnavailable() {
        RuleEvidenceHit source = evidence("SCORING");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                new UnavailableAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId, "SCORING", null, 3, Set.of()));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(modelCalls).hasValue(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "read").count()).isEqualTo(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "write").count()).isEqualTo(1);
    }

    @Test
    void stopsBeforeRetrievalWhenRateLimitStorageIsUnavailable() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        RuleAnswerRateLimiter unavailableLimiter = new RuleAnswerRateLimiter() {
            @Override
            public void checkUser(String username) {
                throw new RuleAnswerRateLimitUnavailableException(5, new IllegalStateException("Redis unavailable"));
            }

            @Override
            public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                throw new AssertionError("model permit must not be acquired");
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    retrievalCalled.set(true);
                    return List.of();
                },
                request -> null,
                new InMemoryAnswerCache(),
                unavailableLimiter,
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.answer(
                        "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of())))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class);
        assertThat(retrievalCalled).isFalse();
    }

    private StructuredRuleAnswerService answerService(HybridRuleSearch retrieval, RuleAnswerModel model) {
        return answerService(retrieval, model, acceptedCritic());
    }

    private StructuredRuleAnswerService answerService(
            HybridRuleSearch retrieval, RuleAnswerModel model, GeneratedContentCritic critic) {
        return new StructuredRuleAnswerService(
                understanding, retrieval, model, new InMemoryAnswerCache(), new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                critic,
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }

    private ConfirmedRulingLookup noConfirmedRulings() {
        return (documentVersionId, expansionIds, question, username) -> Optional.empty();
    }

    private RuleEvidenceHit evidence(String sectionType) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, "Scoring", "Each coin is worth one point.", 8, 8, 0.8);
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

    private static final class UnavailableAnswerCache implements RuleAnswerCache {
        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            throw new IllegalStateException("Redis unavailable");
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            throw new IllegalStateException("Redis unavailable");
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
