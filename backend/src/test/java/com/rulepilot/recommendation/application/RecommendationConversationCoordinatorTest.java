package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import java.math.BigDecimal;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionTurn;
import com.rulepilot.recommendation.application.RecommendationConversationException.Code;
import com.rulepilot.recommendation.application.RecommendationConversationStore.ConversationState;
import com.rulepilot.recommendation.application.RecommendationConversationStore.StoredConversation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RecommendationConversationCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    @Test
    void persistsOneOwnerScopedTurnAndReplaysTheSameClientTurnWithoutCallingTheAgentAgain() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(response("我核对好了。"));
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);
        UUID clientTurnId = UUID.randomUUID();
        ConversationRequest request = request("想找四人游戏");

        var completed = coordinator.converse(
                new SessionTurn(null, 0, clientTurnId, request),
                "zh-CN",
                "Alice",
                ignored -> {});
        var replayed = coordinator.converse(
                new SessionTurn(completed.conversationId(), 0, clientTurnId, request),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(completed.revision()).isEqualTo(1);
        assertThat(completed.replayed()).isFalse();
        assertThat(replayed.revision()).isEqualTo(1);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.response()).isEqualTo(completed.response());
        var latest = coordinator.latest("alice").orElseThrow();
        assertThat(latest.lastClientTurnId()).isEqualTo(clientTurnId);
        assertThat(latest.state().transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("user:想找四人游戏", "assistant:我核对好了。");
        verify(agent, times(1)).conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any());
    }

    @Test
    void carriesVerifiedCandidateFactsIntoLaterTurnsWithoutTrustingTheBrowserToReplayThem() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        Game verified = verifiedGame();
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(responseWithGame("先看这款。", verified), response("可以直接接着聊。"));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());

        var first = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request("推荐一款重策")),
                "zh-CN",
                "alice",
                ignored -> {});
        coordinator.converse(
                new SessionTurn(first.conversationId(), 1, UUID.randomUUID(), request("它的机制是什么？")),
                "zh-CN",
                "alice",
                ignored -> {});

        var requests = org.mockito.ArgumentCaptor.forClass(ConversationRequest.class);
        verify(agent, times(2)).conversePersisted(requests.capture(), eq("zh-CN"), eq("alice"), any(), any());
        assertThat(requests.getAllValues().get(0).priorVerifiedGames()).isEmpty();
        assertThat(requests.getAllValues().get(1).priorVerifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(60);
        assertThat(coordinator.latest("alice").orElseThrow().state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(60);
    }

    @Test
    void retainsNewlyObservedGameIdentityWhenLongTermIdentityMemoryIsFull() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger();
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenAnswer(ignored -> {
                    int id = nextId.incrementAndGet();
                    return responseWithGame("第 " + id + " 款", verifiedGame(id));
                });
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());

        RecommendationConversationCoordinator.TurnResult result = null;
        for (int turn = 0; turn < 61; turn++) {
            result = coordinator.converse(
                    new SessionTurn(
                            result == null ? null : result.conversationId(),
                            result == null ? 0 : result.revision(),
                            UUID.randomUUID(),
                            request("下一款 " + turn)),
                    "zh-CN",
                    "alice",
                    ignored -> {});
        }

        assertThat(coordinator.latest("alice").orElseThrow().state().knownGames())
                .hasSize(RecommendationConversationCoordinator.MAX_KNOWN_GAMES)
                .extracting(BoardGameRecommendationAgent.KnownGame::bggId)
                .contains(61)
                .doesNotContain(1);
    }

    @Test
    void rejectsCrossOwnerLookupStaleRevisionsAndTurnIdReuseWithDifferentInput() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), any(), any(), any(), any())).thenReturn(response("Done."));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        UUID clientTurnId = UUID.randomUUID();
        ConversationRequest firstRequest = request("Find a game");
        var first = coordinator.converse(
                new SessionTurn(null, 0, clientTurnId, firstRequest),
                "en",
                "alice",
                ignored -> {});

        assertThatThrownBy(() -> coordinator.converse(
                        new SessionTurn(first.conversationId(), 0, clientTurnId, request("Different request")),
                        "en",
                        "alice",
                        ignored -> {}))
                .isInstanceOfSatisfying(RecommendationConversationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.TURN_ID_REUSED));
        assertThatThrownBy(() -> coordinator.converse(
                        new SessionTurn(first.conversationId(), 0, UUID.randomUUID(), request("Next turn")),
                        "en",
                        "alice",
                        ignored -> {}))
                .isInstanceOfSatisfying(RecommendationConversationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.REVISION_CONFLICT));
        assertThatThrownBy(() -> coordinator.converse(
                        new SessionTurn(first.conversationId(), 1, UUID.randomUUID(), request("Steal it")),
                        "en",
                        "bob",
                        ignored -> {}))
                .isInstanceOfSatisfying(RecommendationConversationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.NOT_FOUND));
    }

    @Test
    void concurrentRetryWaitsForAndReplaysTheOriginalTurn() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        CountDownLatch enteredAgent = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);
        when(agent.conversePersisted(any(), eq("en"), eq("alice"), any(), any())).thenAnswer(invocation -> {
            enteredAgent.countDown();
            if (!releaseAgent.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return response("One complete answer.");
        });
        passThroughValidation(agent);
        RecommendationConversationCoordinator coordinator = new RecommendationConversationCoordinator(
                agent,
                new InMemoryStore(),
                Clock.systemUTC(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        UUID clientTurnId = UUID.randomUUID();
        SessionTurn turn = new SessionTurn(null, 0, clientTurnId, request("Recommend something"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var original = executor.submit(() -> coordinator.converse(turn, "en", "alice", ignored -> {}));
            assertThat(enteredAgent.await(1, TimeUnit.SECONDS)).isTrue();
            var retry = executor.submit(() -> coordinator.converse(turn, "en", "alice", ignored -> {}));
            releaseAgent.countDown();

            var originalResult = original.get(2, TimeUnit.SECONDS);
            var retryResult = retry.get(2, TimeUnit.SECONDS);
            assertThat(List.of(originalResult.replayed(), retryResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(retryResult.response()).isEqualTo(originalResult.response());
        }
        verify(agent, times(1)).conversePersisted(any(), eq("en"), eq("alice"), any(), any());
    }

    @Test
    void releasesAClaimAfterFailureSoTheSameIdCanRetry() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("en"), eq("alice"), any(), any()))
                .thenThrow(new IllegalStateException("provider failed"))
                .thenReturn(response("Recovered answer."));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        SessionTurn turn = new SessionTurn(null, 0, UUID.randomUUID(), request("Try this"));

        assertThatThrownBy(() -> coordinator.converse(turn, "en", "alice", ignored -> {}))
                .isInstanceOf(IllegalStateException.class);
        var recovered = coordinator.converse(turn, "en", "alice", ignored -> {});

        assertThat(recovered.revision()).isEqualTo(1);
        assertThat(recovered.response().assistantMessage()).isEqualTo("Recovered answer.");
        verify(agent, times(2)).conversePersisted(any(), eq("en"), eq("alice"), any(), any());
    }

    @Test
    void deletesOnlyTheOwnedConversation() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), any(), any(), any(), any())).thenReturn(response("Done."));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        var result = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request("Start")),
                "en",
                "alice",
                ignored -> {});

        assertThatThrownBy(() -> coordinator.delete(result.conversationId(), "bob"))
                .isInstanceOfSatisfying(RecommendationConversationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.NOT_FOUND));
        assertThat(coordinator.latest("alice")).isPresent();

        coordinator.delete(result.conversationId(), "alice");

        assertThat(coordinator.latest("alice")).isEmpty();
    }

    @Test
    void startsANewConversationWithoutDeletingTheExistingConversation() {
        RecommendationConversationCoordinator coordinator = coordinator(
                mock(BoardGameRecommendationAgent.class), new InMemoryStore());

        var first = coordinator.startNew("alice");
        var second = coordinator.startNew("alice");

        assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(coordinator.find(first.conversationId(), "alice")).isPresent();
        assertThat(coordinator.find(second.conversationId(), "alice")).isPresent();
        assertThat(coordinator.recent("alice", 10))
                .extracting(RecommendationConversationCoordinator.SessionSnapshot::conversationId)
                .containsExactlyInAnyOrder(first.conversationId(), second.conversationId());
        assertThat(coordinator.find(first.conversationId(), "bob")).isEmpty();
    }

    @Test
    void rejectsInvalidInputBeforeCreatingPersistentState() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.validatedConversationRequest(any()))
                .thenThrow(new IllegalArgumentException("recommendation conversation text is invalid"));
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = new RecommendationConversationCoordinator(
                agent,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));

        assertThatThrownBy(() -> coordinator.converse(
                        new SessionTurn(null, 0, UUID.randomUUID(), request("invalid")),
                        "en",
                        "alice",
                        ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.findLatestOwned("alice")).isEmpty();
        verify(agent, times(0)).conversePersisted(any(), any(), any(), any(), any());
    }

    private RecommendationConversationCoordinator coordinator(
            BoardGameRecommendationAgent agent,
            RecommendationConversationStore store) {
        passThroughValidation(agent);
        return new RecommendationConversationCoordinator(
                agent,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));
    }

    private void passThroughValidation(BoardGameRecommendationAgent agent) {
        when(agent.validatedConversationRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ConversationRequest request(String message) {
        return new ConversationRequest(RecommendationProfile.empty(), message);
    }

    private ConversationResponse response(String message) {
        return new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                message,
                RecommendationProfile.empty(),
                null,
                10,
                0,
                List.of());
    }

    private ConversationResponse responseWithGame(String message, Game game) {
        return new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                message,
                RecommendationProfile.empty(),
                null,
                10,
                1,
                List.of(new RecommendedGame(game, List.of("已核验"), List.of())));
    }

    private Game verifiedGame() {
        return verifiedGame(60);
    }

    private Game verifiedGame(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Foundry City " + id,
                        2018,
                        12,
                        new BigDecimal("8.1"),
                        new BigDecimal("8.3"),
                        10_000),
                new Details(
                        "Foundry City " + id,
                        "铸城",
                        "https://images.example/60.jpg",
                        2,
                        4,
                        120,
                        new BigDecimal("3.8"),
                        List.of("Economic"),
                        List.of("Network Building"),
                        90,
                        150,
                        14,
                        14,
                        "3",
                        "2-4",
                        2,
                        500,
                        List.of(),
                        List.of("A Designer"),
                        List.of("A Publisher")));
    }

    private static final class InMemoryStore implements RecommendationConversationStore {
        private final Map<UUID, StoredConversation> values = new HashMap<>();

        @Override
        public synchronized StoredConversation createIfAbsent(
                UUID conversationId,
                String ownerUsername,
                ConversationState initialState,
                Instant now) {
            Optional<StoredConversation> existing = findLatestOwned(ownerUsername);
            if (existing.isPresent()) return existing.get();
            StoredConversation created = new StoredConversation(
                    conversationId,
                    ownerUsername,
                    0,
                    initialState,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    now);
            values.put(conversationId, created);
            return created;
        }

        @Override
        public synchronized StoredConversation createNew(
                UUID conversationId,
                String ownerUsername,
                ConversationState initialState,
                Instant now) {
            StoredConversation created = new StoredConversation(
                    conversationId, ownerUsername, 0, initialState,
                    null, null, null, null, null, null, null, now, now);
            values.put(conversationId, created);
            return created;
        }

        @Override
        public synchronized Optional<StoredConversation> findOwned(UUID conversationId, String ownerUsername) {
            return Optional.ofNullable(values.get(conversationId))
                    .filter(value -> value.ownerUsername().equals(ownerUsername));
        }

        @Override
        public synchronized Optional<StoredConversation> findLatestOwned(String ownerUsername) {
            return values.values().stream()
                    .filter(value -> value.ownerUsername().equals(ownerUsername))
                    .max(java.util.Comparator.comparing(StoredConversation::updatedAt));
        }

        @Override
        public synchronized List<StoredConversation> findRecentOwned(String ownerUsername, int limit) {
            return values.values().stream()
                    .filter(value -> value.ownerUsername().equals(ownerUsername))
                    .sorted(java.util.Comparator.comparing(StoredConversation::updatedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized boolean claimTurn(
                UUID conversationId,
                String ownerUsername,
                long expectedRevision,
                UUID clientTurnId,
                String requestFingerprint,
                Instant startedAt,
                Instant staleBefore) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null || current.revision() != expectedRevision) return false;
            boolean available = current.activeClientTurnId() == null
                    || current.activeClientTurnId().equals(clientTurnId)
                            && current.activeRequestFingerprint().equals(requestFingerprint)
                            && !current.activeStartedAt().isAfter(staleBefore);
            if (!available) return false;
            values.put(conversationId, copy(
                    current,
                    current.revision(),
                    current.state(),
                    current.lastClientTurnId(),
                    current.lastRequestFingerprint(),
                    current.lastResponse(),
                    current.lastResponseLocale(),
                    clientTurnId,
                    requestFingerprint,
                    startedAt,
                    startedAt));
            return true;
        }

        @Override
        public synchronized boolean completeTurn(
                UUID conversationId,
                String ownerUsername,
                long expectedRevision,
                UUID clientTurnId,
                String requestFingerprint,
                ConversationState nextState,
                ConversationResponse response,
                String responseLocale,
                Instant completedAt) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null
                    || current.revision() != expectedRevision
                    || !clientTurnId.equals(current.activeClientTurnId())
                    || !requestFingerprint.equals(current.activeRequestFingerprint())) return false;
            values.put(conversationId, copy(
                    current,
                    current.revision() + 1,
                    nextState,
                    clientTurnId,
                    requestFingerprint,
                    response,
                    responseLocale,
                    null,
                    null,
                    null,
                    completedAt));
            return true;
        }

        @Override
        public synchronized void releaseTurn(
                UUID conversationId,
                String ownerUsername,
                long expectedRevision,
                UUID clientTurnId,
                String requestFingerprint,
                Instant releasedAt) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null
                    || current.revision() != expectedRevision
                    || !clientTurnId.equals(current.activeClientTurnId())
                    || !requestFingerprint.equals(current.activeRequestFingerprint())) return;
            values.put(conversationId, copy(
                    current,
                    current.revision(),
                    current.state(),
                    current.lastClientTurnId(),
                    current.lastRequestFingerprint(),
                    current.lastResponse(),
                    current.lastResponseLocale(),
                    null,
                    null,
                    null,
                    releasedAt));
        }

        @Override
        public synchronized boolean deleteOwned(UUID conversationId, String ownerUsername) {
            StoredConversation current = values.get(conversationId);
            if (current == null || !current.ownerUsername().equals(ownerUsername)) return false;
            values.remove(conversationId);
            return true;
        }

        private StoredConversation copy(
                StoredConversation current,
                long revision,
                ConversationState state,
                UUID lastClientTurnId,
                String lastRequestFingerprint,
                ConversationResponse lastResponse,
                String lastResponseLocale,
                UUID activeClientTurnId,
                String activeRequestFingerprint,
                Instant activeStartedAt,
                Instant updatedAt) {
            return new StoredConversation(
                    current.id(),
                    current.ownerUsername(),
                    revision,
                    state,
                    lastClientTurnId,
                    lastRequestFingerprint,
                    lastResponse,
                    lastResponseLocale,
                    activeClientTurnId,
                    activeRequestFingerprint,
                    activeStartedAt,
                    current.createdAt(),
                    updatedAt);
        }
    }
}
