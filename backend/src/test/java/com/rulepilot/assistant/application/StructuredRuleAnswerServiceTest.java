package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    void servesRepeatedValidatedAnswerFromVersionedCacheAndRecordsMetrics() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
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
                cache,
                rateLimiter,
                metrics);
        QuestionContext context = new QuestionContext(versionId, "SCORING", null, 3, Set.of());

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);

        assertThat(second).isEqualTo(first);
        assertThat(modelCalls).hasValue(1);
        assertThat(rateLimiter.userChecks).isEqualTo(2);
        assertThat(rateLimiter.modelAcquires).isEqualTo(1);
        assertThat(rateLimiter.releases).isEqualTo(1);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "miss").count()).isEqualTo(1);
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
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.answer(
                        "How are coins scored?", new QuestionContext(versionId, null, null, null, Set.of())))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class);
        assertThat(retrievalCalled).isFalse();
    }

    private StructuredRuleAnswerService answerService(HybridRuleSearch retrieval, RuleAnswerModel model) {
        return new StructuredRuleAnswerService(
                understanding, retrieval, model, new InMemoryAnswerCache(), new RecordingRateLimiter(),
                new SimpleMeterRegistry());
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
}
