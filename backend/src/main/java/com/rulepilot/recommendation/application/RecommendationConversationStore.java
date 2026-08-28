package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    /** Claims a turn with a fresh opaque attempt id that fences every later mutation. */
    boolean claimTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
            Instant startedAt,
            Instant staleBefore);

    /** Saves facts settled by completed read actions without advancing the player-turn revision. */
    boolean checkpointTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
            ConversationState checkpointState,
            Instant checkpointedAt);

    boolean completeTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
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
            UUID claimAttemptId,
            Instant releasedAt);

    boolean deleteOwned(UUID conversationId, String ownerUsername);

    record ConversationState(
            RecommendationProfile profile,
            List<DialogueMessage> transcript,
            List<KnownGame> knownGames,
            List<Integer> shownBggIds,
            List<Game> verifiedGames,
            PublishedTurn latestPublishedTurn) {
        public ConversationState(
                RecommendationProfile profile,
                List<DialogueMessage> transcript,
                List<KnownGame> knownGames,
                List<Integer> shownBggIds) {
            this(profile, transcript, knownGames, shownBggIds, List.of(), null);
        }

        public ConversationState(
                RecommendationProfile profile,
                List<DialogueMessage> transcript,
                List<KnownGame> knownGames,
                List<Integer> shownBggIds,
                List<Game> verifiedGames) {
            this(profile, transcript, knownGames, shownBggIds, verifiedGames, null);
        }

        public ConversationState {
            profile = profile == null ? RecommendationProfile.empty() : profile;
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
            knownGames = knownGames == null ? List.of() : List.copyOf(knownGames);
            shownBggIds = shownBggIds == null ? List.of() : List.copyOf(shownBggIds);
            verifiedGames = verifiedGames == null ? List.of() : List.copyOf(verifiedGames);
        }
    }

    /** The latest response committed to conversation semantics, separate from the latest idempotency result. */
    record PublishedTurn(UUID clientTurnId, String responseLocale, ConversationResponse response) {
        public PublishedTurn {
            Objects.requireNonNull(clientTurnId, "published recommendation client turn is required");
            if (responseLocale == null || responseLocale.isBlank()) {
                throw new IllegalArgumentException("published recommendation locale is required");
            }
            Objects.requireNonNull(response, "published recommendation response is required");
            if (response.outcome() == Outcome.UNAVAILABLE) {
                throw new IllegalArgumentException("unavailable recommendation cannot be published conversation state");
            }
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
            UUID activeClaimAttemptId,
            Instant activeStartedAt,
            Instant createdAt,
            Instant updatedAt) {}
}
