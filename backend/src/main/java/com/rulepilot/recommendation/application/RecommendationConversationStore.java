package com.rulepilot.recommendation.application;

import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for one owner-scoped, recoverable recommendation conversation. */
public interface RecommendationConversationStore {

    StoredConversation createIfAbsent(
            UUID conversationId,
            String ownerUsername,
            ConversationState initialState,
            Instant now);

    StoredConversation createNew(
            UUID conversationId,
            String ownerUsername,
            ConversationState initialState,
            Instant now);

    Optional<StoredConversation> findOwned(UUID conversationId, String ownerUsername);

    Optional<StoredConversation> findLatestOwned(String ownerUsername);

    List<StoredConversation> findRecentOwned(String ownerUsername, int limit);

    boolean claimTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            Instant startedAt,
            Instant staleBefore);

    boolean completeTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            ConversationState nextState,
            ConversationResponse response,
            String responseLocale,
            Instant completedAt);

    void releaseTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            Instant releasedAt);

    boolean deleteOwned(UUID conversationId, String ownerUsername);

    record ConversationState(
            RecommendationProfile profile,
            List<DialogueMessage> transcript,
            List<KnownGame> knownGames,
            List<Integer> shownBggIds) {
        public ConversationState {
            profile = profile == null ? RecommendationProfile.empty() : profile;
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
            knownGames = knownGames == null ? List.of() : List.copyOf(knownGames);
            shownBggIds = shownBggIds == null ? List.of() : List.copyOf(shownBggIds);
        }
    }

    record StoredConversation(
            UUID id,
            String ownerUsername,
            long revision,
            ConversationState state,
            UUID lastClientTurnId,
            String lastRequestFingerprint,
            ConversationResponse lastResponse,
            String lastResponseLocale,
            UUID activeClientTurnId,
            String activeRequestFingerprint,
            Instant activeStartedAt,
            Instant createdAt,
            Instant updatedAt) {}
}
