package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.TurnCheckpoint;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import java.math.BigDecimal;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionTurn;
import com.rulepilot.recommendation.application.RecommendationConversationException.Code;
import com.rulepilot.recommendation.application.RecommendationConversationStore.ConversationState;
import com.rulepilot.recommendation.application.RecommendationConversationStore.PublishedTurn;
import com.rulepilot.recommendation.application.RecommendationConversationStore.StoredConversation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    void startsANewConversationWhenANewTurnHasNoConversationIdentity() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(response("第一段对话。"), response("新的对话。"));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());

        var first = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request("第一段")),
                "zh-CN",
                "alice",
                ignored -> {});
        var second = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request("新的一段")),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(second.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(second.revision()).isEqualTo(1);
        assertThat(coordinator.find(first.conversationId(), "alice")).isPresent();
        assertThat(coordinator.find(second.conversationId(), "alice")).isPresent();
    }

    @Test
    void replaysTheSameTurnWithoutAConversationIdentity() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("en"), eq("alice"), any(), any()))
                .thenReturn(response("One answer."));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        UUID clientTurnId = UUID.randomUUID();
        SessionTurn turn = new SessionTurn(null, 0, clientTurnId, request("One request"));

        var first = coordinator.converse(turn, "en", "alice", ignored -> {});
        var replay = coordinator.converse(turn, "en", "alice", ignored -> {});

        assertThat(replay.conversationId()).isEqualTo(first.conversationId());
        assertThat(replay.replayed()).isTrue();
        verify(agent, times(1)).conversePersisted(any(), eq("en"), eq("alice"), any(), any());
    }

    @Test
    void usesAnExplicitlyCreatedEmptyConversationForItsFirstTurn() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("en"), eq("alice"), any(), any()))
                .thenReturn(response("Started."));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        var empty = coordinator.startNew("alice");

        var completed = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request("Start here")),
                "en",
                "alice",
                ignored -> {});

        assertThat(completed.conversationId()).isEqualTo(empty.conversationId());
        assertThat(completed.revision()).isEqualTo(1);
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
    void appendsTheCurrentUserMessageAsTheLatestEvidenceForAPersistedSecondTurn() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(response("先前回答。"), response("当前回答。"));
        RecommendationConversationCoordinator coordinator = coordinator(agent, new InMemoryStore());
        String priorMessage = "先看旧港。";
        String currentMessage = "我改选新港，请把它作为当前选择。";

        var first = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request(priorMessage)),
                "zh-CN",
                "alice",
                ignored -> {});
        coordinator.converse(
                new SessionTurn(first.conversationId(), 1, UUID.randomUUID(), request(currentMessage)),
                "zh-CN",
                "alice",
                ignored -> {});

        var requests = org.mockito.ArgumentCaptor.forClass(ConversationRequest.class);
        verify(agent, times(2)).conversePersisted(requests.capture(), eq("zh-CN"), eq("alice"), any(), any());
        ConversationRequest effectiveSecondTurn = requests.getAllValues().get(1);
        assertThat(effectiveSecondTurn.transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly(
                        "user:" + priorMessage,
                        "assistant:先前回答。",
                        "user:" + currentMessage);
        assertThat(new RecommendationEvidenceReview(new ObjectMapper(), null)
                        .preferenceEvidence(effectiveSecondTurn)
                        .entrySet())
                .containsExactly(
                        Map.entry("U1", priorMessage),
                        Map.entry("U2", currentMessage));
    }

    @Test
    void keepsTheLastSettledCatalogCheckpointWhenFinalCompositionFails() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        Game verified = verifiedGame(62);
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<TurnCheckpoint> checkpoints = invocation.getArgument(4);
                    checkpoints.accept(new TurnCheckpoint(RecommendationProfile.empty(), List.of(verified)));
                    throw new IllegalStateException("final composition failed after catalog read");
                });
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);

        assertThatThrownBy(() -> coordinator.converse(
                        new SessionTurn(
                                null,
                                0,
                                UUID.randomUUID(),
                                request("我们四个人第一次聚会，我没想好玩什么；先帮我看看适合聊天的选择。")),
                        "zh-CN",
                        "alice",
                        ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("final composition failed");

        StoredConversation recovered = store.findLatestOwned("alice").orElseThrow();
        assertThat(recovered.revision()).isZero();
        assertThat(recovered.activeClientTurnId()).isNull();
        assertThat(recovered.state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(62);
        assertThat(recovered.state().knownGames())
                .extracting(BoardGameRecommendationAgent.KnownGame::bggId)
                .containsExactly(62);
    }

    @Test
    void completesUnavailableForIdempotencyWithoutWritingItIntoConversationSemantics() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        Game verified = verifiedGame(64);
        RecommendationProfile checkpointProfile =
                new RecommendationProfile(
                        ConstraintRange.hardExact(4),
                        ConstraintRange.hardAtMost(90),
                        null,
                        null,
                        null);
        String requestMessage = "四个人，想找九十分钟内的合作游戏";
        String unavailableMessage = "推荐服务暂时不可用。";
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TurnCheckpoint> checkpoints = invocation.getArgument(4);
                    checkpoints.accept(new TurnCheckpoint(checkpointProfile, List.of(verified)));
                    return unavailableResponse(unavailableMessage);
                })
                .thenReturn(response("这次已经完成。"));
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);
        UUID unavailableTurnId = UUID.randomUUID();

        var unavailable = coordinator.converse(
                new SessionTurn(null, 0, unavailableTurnId, request(requestMessage)),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(unavailable.revision()).isEqualTo(1);
        assertThat(unavailable.response().outcome()).isEqualTo(Outcome.UNAVAILABLE);
        StoredConversation afterUnavailable = store.findLatestOwned("alice").orElseThrow();
        assertThat(afterUnavailable.lastClientTurnId()).isEqualTo(unavailableTurnId);
        assertThat(afterUnavailable.state().profile()).isEqualTo(checkpointProfile);
        assertThat(afterUnavailable.state().transcript()).isEmpty();
        assertThat(afterUnavailable.state().knownGames())
                .extracting(BoardGameRecommendationAgent.KnownGame::bggId)
                .containsExactly(64);
        assertThat(afterUnavailable.state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(64);
        assertThat(afterUnavailable.state().latestPublishedTurn()).isNull();

        var replayedUnavailable = coordinator.converse(
                new SessionTurn(
                        unavailable.conversationId(),
                        0,
                        unavailableTurnId,
                        request(requestMessage)),
                "zh-CN",
                "alice",
                ignored -> {});
        assertThat(replayedUnavailable.replayed()).isTrue();
        assertThat(replayedUnavailable.response().outcome()).isEqualTo(Outcome.UNAVAILABLE);

        UUID retryTurnId = UUID.randomUUID();
        var recovered = coordinator.converse(
                new SessionTurn(
                        unavailable.conversationId(),
                        unavailable.revision(),
                        retryTurnId,
                        request(requestMessage)),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(recovered.revision()).isEqualTo(2);
        var requests = org.mockito.ArgumentCaptor.forClass(ConversationRequest.class);
        verify(agent, times(2)).conversePersisted(requests.capture(), eq("zh-CN"), eq("alice"), any(), any());
        ConversationRequest retryRequest = requests.getAllValues().get(1);
        assertThat(retryRequest.profile()).isEqualTo(checkpointProfile);
        assertThat(retryRequest.priorVerifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(64);
        assertThat(retryRequest.transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("user:" + requestMessage);
        assertThat(store.findLatestOwned("alice").orElseThrow().state().transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("user:" + requestMessage, "assistant:这次已经完成。");
        assertThat(store.findLatestOwned("alice").orElseThrow().state().transcript())
                .noneMatch(message -> message.text().equals(unavailableMessage));
    }

    @Test
    void unavailableTurnPreservesThePriorPublishedResponseWithoutChangingItsIdempotentResult() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        ConversationResponse published = responseWithGame("", verifiedGame(65));
        ConversationResponse unavailable = unavailableResponse("这一轮没有完成。");
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(published)
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TurnCheckpoint> checkpoints = invocation.getArgument(4);
                    checkpoints.accept(new TurnCheckpoint(RecommendationProfile.empty(), List.of(verifiedGame(67))));
                    return unavailable;
                });
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);
        UUID publishedTurnId = UUID.randomUUID();
        UUID unavailableTurnId = UUID.randomUUID();
        String unavailableRequest = "保留上一款，再找一个对比对象";

        var first = coordinator.converse(
                new SessionTurn(null, 0, publishedTurnId, request("先推荐一款")),
                "zh-CN",
                "alice",
                ignored -> {});
        assertThat(first.response().outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
        assertThat(coordinator.latest("alice").orElseThrow().latestPublishedTurn())
                .isEqualTo(new PublishedTurn(publishedTurnId, "zh-CN", published));
        var second = coordinator.converse(
                new SessionTurn(
                        first.conversationId(),
                        first.revision(),
                        unavailableTurnId,
                        request(unavailableRequest)),
                "zh-CN",
                "alice",
                ignored -> {});

        StoredConversation stored = store.findLatestOwned("alice").orElseThrow();
        assertThat(stored.lastResponse()).isEqualTo(unavailable);
        assertThat(stored.lastClientTurnId()).isEqualTo(unavailableTurnId);
        assertThat(stored.state().latestPublishedTurn()).isEqualTo(
                new PublishedTurn(publishedTurnId, "zh-CN", published));
        assertThat(stored.state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(67, 65);
        assertThat(stored.state().transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("user:先推荐一款");

        var replay = coordinator.converse(
                new SessionTurn(
                        first.conversationId(),
                        first.revision(),
                        unavailableTurnId,
                        request(unavailableRequest)),
                "zh-CN",
                "alice",
                ignored -> {});
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(unavailable);
        assertThat(coordinator.latest("alice").orElseThrow().latestPublishedTurn())
                .isEqualTo(new PublishedTurn(publishedTurnId, "zh-CN", published));
        verify(agent, times(2)).conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any());
    }

    @Test
    void persistedConversationStateRoundTripsThePublishedTurnAndReadsOlderStateWithoutOne() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ConversationResponse published = responseWithGame("已经核对完成。", verifiedGame(66));
        ConversationState state = new ConversationState(
                RecommendationProfile.empty(),
                List.of(new DialogueMessage("assistant", published.assistantMessage())),
                List.of(),
                List.of(66),
                List.of(published.games().getFirst().game()),
                new PublishedTurn(UUID.randomUUID(), "zh-CN", published));

        ConversationState restored = json.readValue(json.writeValueAsBytes(state), ConversationState.class);
        assertThat(restored).isEqualTo(state);

        var legacyJson = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(state);
        legacyJson.remove("latestPublishedTurn");
        ConversationState restoredLegacy = json.treeToValue(legacyJson, ConversationState.class);
        assertThat(restoredLegacy.latestPublishedTurn()).isNull();
        assertThat(restoredLegacy.verifiedGames()).isEqualTo(state.verifiedGames());
    }

    @Test
    void implicitConversationDoesNotImportTheOptimisticCurrentTurnWhenItEndsUnavailable() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        String initialGreeting = "晚上好，想从哪种桌游开始聊？";
        String requestMessage = "找一些适合四个人的科幻主题桌游";
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(unavailableResponse("推荐服务暂时不可用。"));
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);
        ConversationRequest browserRequest = new ConversationRequest(
                RecommendationProfile.empty(),
                requestMessage,
                List.of(),
                List.of(
                        new DialogueMessage("assistant", initialGreeting),
                        new DialogueMessage("user", requestMessage)),
                null,
                List.of(),
                List.of());

        var unavailable = coordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), browserRequest),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(unavailable.response().outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(store.findLatestOwned("alice").orElseThrow().state().transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("assistant:" + initialGreeting)
                .noneMatch(turn -> turn.equals("user:" + requestMessage));
        var effectiveRequest = org.mockito.ArgumentCaptor.forClass(ConversationRequest.class);
        verify(agent).conversePersisted(effectiveRequest.capture(), eq("zh-CN"), eq("alice"), any(), any());
        assertThat(effectiveRequest.getValue().transcript())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("assistant:" + initialGreeting, "user:" + requestMessage);
    }

    @Test
    void optimisticCurrentTurnIsRemovedBeforeBoundingTheImportedHistory() {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        String requestMessage = "这是当前浏览器乐观追加的回合";
        when(agent.conversePersisted(any(), eq("zh-CN"), eq("alice"), any(), any()))
                .thenReturn(unavailableResponse("推荐服务暂时不可用。"));
        List<DialogueMessage> fullHistory = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(index -> new DialogueMessage(
                        index % 2 == 0 ? "assistant" : "user",
                        "历史回合 " + index))
                .toList();
        List<DialogueMessage> optimisticTranscript = new ArrayList<>(fullHistory);
        optimisticTranscript.add(new DialogueMessage("user", requestMessage));
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator coordinator = coordinator(agent, store);

        coordinator.converse(
                new SessionTurn(
                        null,
                        0,
                        UUID.randomUUID(),
                        new ConversationRequest(
                                RecommendationProfile.empty(),
                                requestMessage,
                                List.of(),
                                optimisticTranscript,
                                null,
                                List.of(),
                                List.of())),
                "zh-CN",
                "alice",
                ignored -> {});

        assertThat(store.findLatestOwned("alice").orElseThrow().state().transcript())
                .containsExactlyElementsOf(fullHistory);
    }

    @Test
    void staleRecoveryUsesTheClaimedCheckpointStateAndFencesTheOriginalAttempt() throws Exception {
        BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
        Game verified = verifiedGame(63);
        CountDownLatch checkpointSaved = new CountDownLatch(1);
        CountDownLatch releaseOriginal = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ConversationRequest> recoveryRequest = new AtomicReference<>();
        when(agent.conversePersisted(any(), eq("en"), eq("alice"), any(), any()))
                .thenAnswer(invocation -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) return response("Earlier answer.");
                    if (call == 2) {
                        Consumer<TurnCheckpoint> checkpoints = invocation.getArgument(4);
                        checkpoints.accept(new TurnCheckpoint(RecommendationProfile.empty(), List.of(verified)));
                        checkpointSaved.countDown();
                        if (!releaseOriginal.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timed out waiting to finish the original attempt");
                        }
                        return response("Obsolete answer.");
                    }
                    recoveryRequest.set(invocation.getArgument(0));
                    return response("Recovered answer.");
                });
        passThroughValidation(agent);
        InMemoryStore store = new InMemoryStore();
        RecommendationConversationCoordinator originalCoordinator = new RecommendationConversationCoordinator(
                agent,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));
        RecommendationConversationCoordinator recoveryCoordinator = new RecommendationConversationCoordinator(
                agent,
                store,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));
        String priorMessage = "Earlier user request";
        String currentMessage = "Current recoverable target";
        var earlier = originalCoordinator.converse(
                new SessionTurn(null, 0, UUID.randomUUID(), request(priorMessage)),
                "en",
                "alice",
                ignored -> {});
        UUID clientTurnId = UUID.randomUUID();
        ConversationRequest request = request(currentMessage);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var original = executor.submit(() -> originalCoordinator.converse(
                    new SessionTurn(earlier.conversationId(), 1, clientTurnId, request),
                    "en",
                    "alice",
                    ignored -> {}));
            assertThat(checkpointSaved.await(1, TimeUnit.SECONDS)).isTrue();
            UUID conversationId = earlier.conversationId();

            var recovered = recoveryCoordinator.converse(
                    new SessionTurn(conversationId, 1, clientTurnId, request),
                    "en",
                    "alice",
                    ignored -> {});
            releaseOriginal.countDown();
            var fencedOriginal = original.get(2, TimeUnit.SECONDS);

            assertThat(recoveryRequest.get().priorVerifiedGames())
                    .extracting(game -> game.ranking().bggId())
                    .containsExactly(63);
            assertThat(recoveryRequest.get().transcript())
                    .extracting(message -> message.role() + ":" + message.text())
                    .containsExactly(
                            "user:" + priorMessage,
                            "assistant:Earlier answer.",
                            "user:" + currentMessage);
            assertThat(new RecommendationEvidenceReview(new ObjectMapper(), null)
                            .preferenceEvidence(recoveryRequest.get())
                            .entrySet())
                    .containsExactly(
                            Map.entry("U1", priorMessage),
                            Map.entry("U2", currentMessage));
            assertThat(recovered.response().assistantMessage()).isEqualTo("Recovered answer.");
            assertThat(fencedOriginal.replayed()).isTrue();
            assertThat(fencedOriginal.response()).isEqualTo(recovered.response());
            assertThat(store.findOwned(conversationId, "alice").orElseThrow().state().verifiedGames())
                    .extracting(game -> game.ranking().bggId())
                    .containsExactly(63);
        } finally {
            releaseOriginal.countDown();
        }
    }

    @Test
    void retainsTheCompleteTranscriptAndEveryObservedIdentityAcrossLongConversations() {
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

        ConversationState state = coordinator.latest("alice").orElseThrow().state();
        assertThat(state.transcript()).hasSize(122);
        assertThat(state.knownGames())
                .hasSize(61)
                .extracting(BoardGameRecommendationAgent.KnownGame::bggId)
                .contains(1, 61);
        assertThat(state.shownBggIds())
                .hasSize(61)
                .contains(1, 61);
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
    void sessionReadsReleaseOnlyAStaleCrashedClaimAndRetainItsCheckpointForExactRetry() {
        InMemoryStore store = new InMemoryStore();
        UUID staleConversationId = UUID.randomUUID();
        UUID staleTurnId = UUID.randomUUID();
        UUID staleAttemptId = UUID.randomUUID();
        String fingerprint = "stale-request-fingerprint";
        ConversationState initial = new ConversationState(
                RecommendationProfile.empty(), List.of(), List.of(), List.of());
        ConversationState checkpoint = new ConversationState(
                RecommendationProfile.empty(),
                List.of(new DialogueMessage("user", "checkpointed request")),
                List.of(),
                List.of());
        store.createNew(staleConversationId, "alice", initial, NOW);
        assertThat(store.claimTurn(
                        staleConversationId,
                        "alice",
                        0,
                        staleTurnId,
                        fingerprint,
                        staleAttemptId,
                        NOW,
                        NOW.minusSeconds(10)))
                .isTrue();
        assertThat(store.checkpointTurn(
                        staleConversationId,
                        "alice",
                        0,
                        staleTurnId,
                        fingerprint,
                        staleAttemptId,
                        checkpoint,
                        NOW.plusSeconds(1)))
                .isTrue();

        UUID freshConversationId = UUID.randomUUID();
        UUID freshTurnId = UUID.randomUUID();
        UUID freshAttemptId = UUID.randomUUID();
        store.createNew(freshConversationId, "alice", initial, NOW.plusSeconds(15));
        assertThat(store.claimTurn(
                        freshConversationId,
                        "alice",
                        0,
                        freshTurnId,
                        "fresh-request-fingerprint",
                        freshAttemptId,
                        NOW.plusSeconds(15),
                        NOW))
                .isTrue();
        RecommendationConversationCoordinator coordinator = new RecommendationConversationCoordinator(
                mock(BoardGameRecommendationAgent.class),
                store,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10));

        var recovered = coordinator.find(staleConversationId, "alice").orElseThrow();
        var stillRunning = coordinator.find(freshConversationId, "alice").orElseThrow();

        assertThat(recovered.activeClientTurnId()).isNull();
        assertThat(recovered.state()).isEqualTo(checkpoint);
        assertThat(stillRunning.activeClientTurnId()).isEqualTo(freshTurnId);
        assertThat(store.checkpointTurn(
                        staleConversationId,
                        "alice",
                        0,
                        staleTurnId,
                        fingerprint,
                        staleAttemptId,
                        initial,
                        NOW.plusSeconds(21)))
                .isFalse();
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

    private ConversationResponse unavailableResponse(String message) {
        return new ConversationResponse(
                Outcome.UNAVAILABLE,
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
                List.of(new RecommendedGame(game, List.of(), List.of())));
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
                    null, null, null, null, null, null, null, null, now, now);
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
                UUID claimAttemptId,
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
                    claimAttemptId,
                    startedAt,
                    startedAt));
            return true;
        }

        @Override
        public synchronized boolean checkpointTurn(
                UUID conversationId,
                String ownerUsername,
                long expectedRevision,
                UUID clientTurnId,
                String requestFingerprint,
                UUID claimAttemptId,
                ConversationState checkpointState,
                Instant checkpointedAt) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null
                    || current.revision() != expectedRevision
                    || !clientTurnId.equals(current.activeClientTurnId())
                    || !requestFingerprint.equals(current.activeRequestFingerprint())
                    || !claimAttemptId.equals(current.activeClaimAttemptId())) return false;
            values.put(conversationId, copy(
                    current,
                    current.revision(),
                    checkpointState,
                    current.lastClientTurnId(),
                    current.lastRequestFingerprint(),
                    current.lastResponse(),
                    current.lastResponseLocale(),
                    clientTurnId,
                    requestFingerprint,
                    claimAttemptId,
                    current.activeStartedAt(),
                    checkpointedAt));
            return true;
        }

        @Override
        public synchronized boolean completeTurn(
                UUID conversationId,
                String ownerUsername,
                long expectedRevision,
                UUID clientTurnId,
                String requestFingerprint,
                UUID claimAttemptId,
                ConversationState nextState,
                ConversationResponse response,
                String responseLocale,
                Instant completedAt) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null
                    || current.revision() != expectedRevision
                    || !clientTurnId.equals(current.activeClientTurnId())
                    || !requestFingerprint.equals(current.activeRequestFingerprint())
                    || !claimAttemptId.equals(current.activeClaimAttemptId())) return false;
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
                UUID claimAttemptId,
                Instant releasedAt) {
            StoredConversation current = findOwned(conversationId, ownerUsername).orElse(null);
            if (current == null
                    || current.revision() != expectedRevision
                    || !clientTurnId.equals(current.activeClientTurnId())
                    || !requestFingerprint.equals(current.activeRequestFingerprint())
                    || !claimAttemptId.equals(current.activeClaimAttemptId())) return;
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
                UUID activeClaimAttemptId,
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
                    activeClaimAttemptId,
                    activeStartedAt,
                    current.createdAt(),
                    updatedAt);
        }
    }
}
