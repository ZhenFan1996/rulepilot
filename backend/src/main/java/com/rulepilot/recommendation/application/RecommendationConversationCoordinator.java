package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressUpdate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.RecommendationConversationException.Code;
import com.rulepilot.recommendation.application.RecommendationConversationStore.ConversationState;
import com.rulepilot.recommendation.application.RecommendationConversationStore.StoredConversation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Owns session identity, optimistic revisions, and idempotent recommendation turns. */
@Service
@Profile("!test")
public class RecommendationConversationCoordinator {

    static final int MAX_TRANSCRIPT_TURNS = 12;
    static final int MAX_KNOWN_GAMES = 60;
    static final int MAX_SHOWN_GAMES = 60;
    private static final Duration EXTRA_RECOVERY_WINDOW = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final BoardGameRecommendationAgent agent;
    private final RecommendationConversationStore conversations;
    private final Clock clock;
    private final Duration waitForOriginalTurn;
    private final Duration staleTurnAge;

    @Autowired
    public RecommendationConversationCoordinator(
            BoardGameRecommendationAgent agent,
            RecommendationConversationStore conversations,
            BoardGameRecommendationProperties properties) {
        this(
                agent,
                conversations,
                Clock.systemUTC(),
                properties.timeout().plus(EXTRA_RECOVERY_WINDOW),
                properties.timeout().plus(EXTRA_RECOVERY_WINDOW));
    }

    RecommendationConversationCoordinator(
            BoardGameRecommendationAgent agent,
            RecommendationConversationStore conversations,
            Clock clock,
            Duration waitForOriginalTurn,
            Duration staleTurnAge) {
        this.agent = Objects.requireNonNull(agent);
        this.conversations = Objects.requireNonNull(conversations);
        this.clock = Objects.requireNonNull(clock);
        this.waitForOriginalTurn = positive(waitForOriginalTurn, "turn wait duration");
        this.staleTurnAge = positive(staleTurnAge, "stale turn age");
    }

    public TurnResult converse(
            SessionTurn turn,
            String requestedLocale,
            String ownerUsername,
            Consumer<ProgressUpdate> progressListener) {
        return converse(
                turn,
                requestedLocale,
                ownerUsername,
                progressListener,
                ignored -> {});
    }

    public TurnResult converse(
            SessionTurn turn,
            String requestedLocale,
            String ownerUsername,
            Consumer<ProgressUpdate> progressListener,
            Consumer<String> answerPartListener) {
        Objects.requireNonNull(turn, "recommendation session turn is required");
        Objects.requireNonNull(turn.clientTurnId(), "clientTurnId is required for a persisted turn");
        ConversationRequest validatedRequest = agent.validatedConversationRequest(turn.request());
        SessionTurn validatedTurn = new SessionTurn(
                turn.conversationId(),
                turn.expectedRevision(),
                turn.clientTurnId(),
                validatedRequest);
        String owner = owner(ownerUsername);
        String locale = responseLocale(requestedLocale);
        StoredConversation conversation = resolveConversation(validatedTurn, owner);
        ConversationRequest effectiveRequest = requestFrom(conversation.state(), validatedRequest);
        String fingerprint = fingerprint(validatedRequest, locale);

        Optional<TurnResult> replay = replay(conversation, validatedTurn.clientTurnId(), fingerprint);
        if (replay.isPresent()) return replay.get();
        requireRevision(validatedTurn.expectedRevision(), conversation.revision());

        StoredConversation claimed = claimOrAwait(
                conversation,
                validatedTurn.clientTurnId(),
                fingerprint,
                validatedTurn.expectedRevision(),
                progressListener);
        Optional<TurnResult> completedWhileClaiming = replay(claimed, validatedTurn.clientTurnId(), fingerprint);
        if (completedWhileClaiming.isPresent()) return completedWhileClaiming.get();

        try {
            ConversationResponse response = agent.conversePersisted(
                    effectiveRequest,
                    locale,
                    owner,
                    progressListener,
                    answerPartListener);
            ConversationState nextState = nextState(claimed.state(), effectiveRequest, response);
            Instant completedAt = clock.instant();
            boolean completed = conversations.completeTurn(
                    claimed.id(),
                    owner,
                    claimed.revision(),
                    validatedTurn.clientTurnId(),
                    fingerprint,
                    nextState,
                    response,
                    locale,
                    completedAt);
            if (!completed) {
                StoredConversation current = requireOwned(claimed.id(), owner);
                Optional<TurnResult> concurrentReplay = replay(current, validatedTurn.clientTurnId(), fingerprint);
                if (concurrentReplay.isPresent()) return concurrentReplay.get();
                throw conflict(Code.CONCURRENT_TURN, "recommendation conversation changed while the turn completed");
            }
            return new TurnResult(
                    claimed.id(),
                    claimed.revision() + 1,
                    validatedTurn.clientTurnId(),
                    false,
                    locale,
                    response);
        } catch (RuntimeException | Error failure) {
            conversations.releaseTurn(
                    claimed.id(),
                    owner,
                    claimed.revision(),
                    validatedTurn.clientTurnId(),
                    fingerprint,
                    clock.instant());
            throw failure;
        }
    }

    public Optional<SessionSnapshot> latest(String ownerUsername) {
        return conversations.findLatestOwned(owner(ownerUsername)).map(SessionSnapshot::from);
    }

    public SessionSnapshot startNew(String ownerUsername) {
        String owner = owner(ownerUsername);
        ConversationState empty = new ConversationState(
                RecommendationProfile.empty(), List.of(), List.of(), List.of());
        return SessionSnapshot.from(conversations.createNew(UUID.randomUUID(), owner, empty, clock.instant()));
    }

    public Optional<SessionSnapshot> find(UUID conversationId, String ownerUsername) {
        if (conversationId == null) return Optional.empty();
        return conversations.findOwned(conversationId, owner(ownerUsername)).map(SessionSnapshot::from);
    }

    public List<SessionSnapshot> recent(String ownerUsername, int limit) {
        return conversations.findRecentOwned(owner(ownerUsername), limit).stream()
                .map(SessionSnapshot::from)
                .toList();
    }

    public void delete(UUID conversationId, String ownerUsername) {
        if (conversationId == null || !conversations.deleteOwned(conversationId, owner(ownerUsername))) {
            throw conflict(Code.NOT_FOUND, "recommendation conversation does not exist");
        }
    }

    private StoredConversation resolveConversation(SessionTurn turn, String owner) {
        if (turn.conversationId() != null) return requireOwned(turn.conversationId(), owner);
        Optional<StoredConversation> existing = conversations.findLatestOwned(owner);
        if (existing.isPresent()) return existing.get();
        if (turn.expectedRevision() != 0) {
            throw conflict(Code.REVISION_CONFLICT, "a new recommendation conversation must start at revision zero");
        }
        ConversationRequest request = Objects.requireNonNull(turn.request(), "recommendation request is required");
        return conversations.createIfAbsent(
                UUID.randomUUID(),
                owner,
                importedState(request),
                clock.instant());
    }

    private StoredConversation claimOrAwait(
            StoredConversation initial,
            UUID clientTurnId,
            String fingerprint,
            long expectedRevision,
            Consumer<ProgressUpdate> progressListener) {
        StoredConversation current = initial;
        Instant waitDeadline = clock.instant().plus(waitForOriginalTurn);
        while (true) {
            Optional<TurnResult> replay = replay(current, clientTurnId, fingerprint);
            if (replay.isPresent()) return current;
            requireRevision(expectedRevision, current.revision());

            if (current.activeClientTurnId() == null || sameActiveTurn(current, clientTurnId, fingerprint)) {
                Instant now = clock.instant();
                if (conversations.claimTurn(
                        current.id(),
                        current.ownerUsername(),
                        current.revision(),
                        clientTurnId,
                        fingerprint,
                        now,
                        now.minus(staleTurnAge))) {
                    return requireOwned(current.id(), current.ownerUsername());
                }
            } else {
                throw conflict(Code.CONCURRENT_TURN, "another recommendation turn is already in progress");
            }

            current = requireOwned(current.id(), current.ownerUsername());
            Optional<TurnResult> afterClaimRace = replay(current, clientTurnId, fingerprint);
            if (afterClaimRace.isPresent()) return current;
            if (!sameActiveTurn(current, clientTurnId, fingerprint)) {
                if (current.activeClientTurnId() == null) continue;
                if (current.activeClientTurnId().equals(clientTurnId)) {
                    throw conflict(Code.TURN_ID_REUSED, "clientTurnId was reused for a different request");
                }
                throw conflict(Code.CONCURRENT_TURN, "another recommendation turn is already in progress");
            }
            if (!clock.instant().isBefore(waitDeadline)) {
                throw conflict(Code.TURN_IN_PROGRESS, "the original recommendation turn is still in progress");
            }
            emitWaitingProgress(progressListener);
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw conflict(Code.TURN_IN_PROGRESS, "waiting for the original recommendation turn was interrupted");
            }
        }
    }

    private Optional<TurnResult> replay(
            StoredConversation conversation,
            UUID clientTurnId,
            String fingerprint) {
        if (!clientTurnId.equals(conversation.lastClientTurnId())) return Optional.empty();
        if (!fingerprint.equals(conversation.lastRequestFingerprint())) {
            throw conflict(Code.TURN_ID_REUSED, "clientTurnId was reused for a different request");
        }
        if (conversation.lastResponse() == null || conversation.lastResponseLocale() == null) {
            throw new IllegalStateException("completed recommendation turn has no response");
        }
        return Optional.of(new TurnResult(
                conversation.id(),
                conversation.revision(),
                clientTurnId,
                true,
                conversation.lastResponseLocale(),
                conversation.lastResponse()));
    }

    private StoredConversation requireOwned(UUID id, String owner) {
        return conversations.findOwned(id, owner)
                .orElseThrow(() -> conflict(Code.NOT_FOUND, "recommendation conversation does not exist"));
    }

    private static void requireRevision(long expected, long actual) {
        if (expected != actual) {
            throw conflict(Code.REVISION_CONFLICT, "recommendation conversation revision is stale");
        }
    }

    private static boolean sameActiveTurn(
            StoredConversation conversation,
            UUID clientTurnId,
            String fingerprint) {
        if (!clientTurnId.equals(conversation.activeClientTurnId())) return false;
        if (!fingerprint.equals(conversation.activeRequestFingerprint())) {
            throw conflict(Code.TURN_ID_REUSED, "clientTurnId was reused for a different request");
        }
        return true;
    }

    private static ConversationRequest requestFrom(ConversationState state, ConversationRequest turn) {
        Objects.requireNonNull(turn, "recommendation request is required");
        return new ConversationRequest(
                state.profile(),
                turn.message(),
                turn.excludedBggIds(),
                state.transcript(),
                turn.focusedBggId(),
                state.knownGames(),
                state.shownBggIds(),
                state.verifiedGames());
    }

    private static ConversationState importedState(ConversationRequest request) {
        return new ConversationState(
                request.profile(),
                boundedTranscript(request.transcript()),
                boundedKnownGames(request.knownGames()),
                boundedIds(request.shownBggIds()),
                List.of());
    }

    private static ConversationState nextState(
            ConversationState previous,
            ConversationRequest request,
            ConversationResponse response) {
        List<DialogueMessage> transcript = new ArrayList<>(previous.transcript());
        appendUnlessDuplicate(transcript, new DialogueMessage("user", request.message()));
        appendUnlessDuplicate(transcript, new DialogueMessage("assistant", response.assistantMessage()));

        Map<Integer, KnownGame> games = new LinkedHashMap<>();
        previous.knownGames().forEach(game -> games.putIfAbsent(game.bggId(), game));
        response.games().stream().map(BoardGameRecommendationAgent.RecommendedGame::game)
                .map(RecommendationConversationCoordinator::knownGame)
                .forEach(game -> games.putIfAbsent(game.bggId(), game));
        if (response.comparison() != null) {
            response.comparison().candidates().stream()
                    .map(BoardGameRecommendationAgent.ComparisonCandidate::game)
                    .map(RecommendationConversationCoordinator::knownGame)
                    .forEach(game -> games.putIfAbsent(game.bggId(), game));
        }

        LinkedHashSet<Integer> shown = new LinkedHashSet<>(previous.shownBggIds());
        response.games().forEach(game -> shown.add(game.game().ranking().bggId()));
        if (response.comparison() != null) {
            response.comparison().candidates()
                    .forEach(candidate -> shown.add(candidate.game().ranking().bggId()));
        }

        Map<Integer, Game> verified = new LinkedHashMap<>();
        response.games().stream().map(BoardGameRecommendationAgent.RecommendedGame::game)
                .forEach(game -> verified.putIfAbsent(game.ranking().bggId(), game));
        if (response.comparison() != null) {
            response.comparison().candidates().stream()
                    .map(BoardGameRecommendationAgent.ComparisonCandidate::game)
                    .forEach(game -> verified.putIfAbsent(game.ranking().bggId(), game));
        }
        previous.verifiedGames().forEach(game -> verified.putIfAbsent(game.ranking().bggId(), game));
        return new ConversationState(
                response.profile(),
                boundedTranscript(transcript),
                boundedKnownGames(new ArrayList<>(games.values())),
                boundedIds(new ArrayList<>(shown)),
                verified.values().stream().limit(RecommendationAgentState.MAX_VERIFIED_GAMES).toList());
    }

    private static KnownGame knownGame(Game game) {
        String name = game.details() == null || game.details().name().isBlank()
                ? game.ranking().sourceName()
                : game.details().name();
        return new KnownGame(game.ranking().bggId(), name, game.ranking().sourceName());
    }

    private static void appendUnlessDuplicate(List<DialogueMessage> transcript, DialogueMessage message) {
        if (message.text() == null || message.text().isBlank()) return;
        if (!transcript.isEmpty() && transcript.getLast().equals(message)) return;
        transcript.add(message);
    }

    private static List<DialogueMessage> boundedTranscript(List<DialogueMessage> values) {
        List<DialogueMessage> copy = values == null ? List.of() : List.copyOf(values);
        return copy.size() <= MAX_TRANSCRIPT_TURNS
                ? copy
                : List.copyOf(copy.subList(copy.size() - MAX_TRANSCRIPT_TURNS, copy.size()));
    }

    private static List<KnownGame> boundedKnownGames(List<KnownGame> values) {
        Map<Integer, KnownGame> unique = new LinkedHashMap<>();
        if (values != null) values.forEach(game -> unique.putIfAbsent(game.bggId(), game));
        return unique.values().stream().limit(MAX_KNOWN_GAMES).toList();
    }

    private static List<Integer> boundedIds(List<Integer> values) {
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        if (values != null) unique.addAll(values);
        List<Integer> ids = new ArrayList<>(unique);
        return ids.size() <= MAX_SHOWN_GAMES
                ? List.copyOf(ids)
                : List.copyOf(ids.subList(ids.size() - MAX_SHOWN_GAMES, ids.size()));
    }

    private static String fingerprint(ConversationRequest request, String locale) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest(digest, locale);
            profileFingerprint(digest, request.profile());
            digest(digest, request.message());
            integers(digest, request.excludedBggIds());
            integer(digest, request.focusedBggId());
            integer(digest, request.transcript().size());
            request.transcript().forEach(message -> {
                digest(digest, message.role());
                digest(digest, message.text());
            });
            integer(digest, request.knownGames().size());
            request.knownGames().forEach(game -> {
                integer(digest, game.bggId());
                digest(digest, game.name());
                digest(digest, game.originalName());
            });
            integers(digest, request.shownBggIds());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void profileFingerprint(MessageDigest digest, RecommendationProfile profile) {
        RecommendationProfile value = profile == null ? RecommendationProfile.empty() : profile;
        range(digest, value.playerCount());
        range(digest, value.durationMinutes());
        range(digest, value.complexity());
        digest(digest, value.type().name());
        digest(digest, value.interaction().name());
    }

    private static void range(MessageDigest digest, ConstraintRange<?> range) {
        if (range == null) {
            digest(digest, null);
            return;
        }
        digest(digest, String.valueOf(range.minimum()));
        digest(digest, String.valueOf(range.maximum()));
        digest(digest, range.strength().name());
        digest(digest, range.sourceText());
        integer(digest, range.confirmedTurn());
    }

    private static void integers(MessageDigest digest, List<Integer> values) {
        integer(digest, values == null ? 0 : values.size());
        if (values != null) values.forEach(value -> integer(digest, value));
    }

    private static void integer(MessageDigest digest, Integer value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? Integer.MIN_VALUE : value).array());
    }

    private static void digest(MessageDigest digest, String value) {
        if (value == null) {
            integer(digest, null);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void emitWaitingProgress(Consumer<ProgressUpdate> progressListener) {
        if (progressListener == null) return;
        progressListener.accept(new ProgressUpdate(
                BoardGameRecommendationAgent.ProgressStage.COMPOSING_RESPONSE,
                0));
    }

    private static String responseLocale(String requestedLocale) {
        return requestedLocale != null && requestedLocale.toLowerCase(Locale.ROOT).startsWith("zh")
                ? "zh-CN"
                : "en";
    }

    private static String owner(String value) {
        String owner = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (owner.isBlank() || owner.codePointCount(0, owner.length()) > 40) {
            throw new IllegalArgumentException("recommendation conversation owner is invalid");
        }
        return owner;
    }

    private static Duration positive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static RecommendationConversationException conflict(Code code, String message) {
        return new RecommendationConversationException(code, message);
    }

    public record SessionTurn(
            UUID conversationId,
            long expectedRevision,
            UUID clientTurnId,
            ConversationRequest request) {}

    public record TurnResult(
            UUID conversationId,
            long revision,
            UUID clientTurnId,
            boolean replayed,
            String responseLocale,
            ConversationResponse response) {}

    public record SessionSnapshot(
            UUID conversationId,
            long revision,
            ConversationState state,
            UUID lastClientTurnId,
            UUID activeClientTurnId,
            Instant activeStartedAt,
            ConversationResponse lastResponse,
            String lastResponseLocale) {
        static SessionSnapshot from(StoredConversation conversation) {
            return new SessionSnapshot(
                    conversation.id(),
                    conversation.revision(),
                    conversation.state(),
                    conversation.lastClientTurnId(),
                    conversation.activeClientTurnId(),
                    conversation.activeStartedAt(),
                    conversation.lastResponse(),
                    conversation.lastResponseLocale());
        }
    }
}
