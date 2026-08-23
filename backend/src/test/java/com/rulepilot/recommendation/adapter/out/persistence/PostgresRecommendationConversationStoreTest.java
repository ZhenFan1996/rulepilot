package com.rulepilot.recommendation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import com.rulepilot.recommendation.application.RecommendationConversationStore.ConversationState;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresRecommendationConversationStoreTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    @Container
    private static final PostgreSQLContainer<?> UPGRADE_POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
                    .withDatabaseName("rulepilot_upgrade")
                    .withUsername("rulepilot")
                    .withPassword("rulepilot-test");

    private static NamedParameterJdbcTemplate jdbc;
    private static PostgresRecommendationConversationStore store;

    @BeforeAll
    static void migrate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        store = new PostgresRecommendationConversationStore(
                jdbc,
                new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void reset() {
        JdbcTemplate template = jdbc.getJdbcTemplate();
        template.update("delete from recommendation_conversation");
        template.update("delete from app_user_authority");
        template.update("delete from app_user");
        template.update("insert into app_user (username, password_hash, enabled) values ('alice', 'hash', true)");
        template.update("insert into app_user (username, password_hash, enabled) values ('bob', 'hash', true)");
    }

    @Test
    void upgradesAnExistingV102ActiveTurnAndKeepsItsLegacyReleaseCompatible() {
        enableProductionExtensions(UPGRADE_POSTGRES);
        Flyway.configure()
                .dataSource(
                        UPGRADE_POSTGRES.getJdbcUrl(),
                        UPGRADE_POSTGRES.getUsername(),
                        UPGRADE_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("102"))
                .load()
                .migrate();
        JdbcTemplate upgradeJdbc = new JdbcTemplate(new DriverManagerDataSource(
                UPGRADE_POSTGRES.getJdbcUrl(),
                UPGRADE_POSTGRES.getUsername(),
                UPGRADE_POSTGRES.getPassword()));
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        String fingerprint = "8".repeat(64);
        Instant startedAt = Instant.parse("2026-08-15T08:00:00Z");
        upgradeJdbc.update(
                "insert into app_user (username, password_hash, enabled) values ('alice', 'hash', true)");
        upgradeJdbc.update(
                """
                insert into recommendation_conversation (
                    id, owner_username, revision, state_json,
                    active_client_turn_id, active_request_fingerprint, active_started_at,
                    created_at, updated_at
                ) values (?, 'alice', 0, cast(? as jsonb), ?, ?, ?, ?, ?)
                """,
                conversationId,
                write(state(List.of())),
                clientTurnId,
                fingerprint,
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt));

        Flyway.configure()
                .dataSource(
                        UPGRADE_POSTGRES.getJdbcUrl(),
                        UPGRADE_POSTGRES.getUsername(),
                        UPGRADE_POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID backfilledAttemptId = upgradeJdbc.queryForObject(
                "select active_claim_attempt_id from recommendation_conversation where id = ?",
                UUID.class,
                conversationId);
        assertThat(backfilledAttemptId).isNotNull();
        assertThat(legacyRelease(
                        upgradeJdbc,
                        conversationId,
                        0,
                        clientTurnId,
                        fingerprint,
                        startedAt.plusSeconds(1)))
                .isEqualTo(1);
        assertThat(upgradeJdbc.queryForObject(
                        "select active_client_turn_id is null from recommendation_conversation where id = ?",
                        Boolean.class,
                        conversationId))
                .isTrue();
        assertThat(upgradeJdbc.queryForObject(
                        "select active_claim_attempt_id from recommendation_conversation where id = ?",
                        UUID.class,
                        conversationId))
                .isEqualTo(backfilledAttemptId);
    }

    @Test
    void keepsLegacyClaimCompleteAndReleaseSqlCompatibleDuringTheRollbackWindow() {
        JdbcTemplate legacyJdbc = jdbc.getJdbcTemplate();
        Instant startedAt = Instant.parse("2026-08-15T08:00:00Z");
        UUID legacyConversationId = UUID.randomUUID();
        UUID legacyClientTurnId = UUID.randomUUID();
        String legacyFingerprint = "7".repeat(64);
        store.createNew(legacyConversationId, "alice", state(List.of()), startedAt);

        assertThat(legacyClaim(
                        legacyJdbc,
                        legacyConversationId,
                        0,
                        legacyClientTurnId,
                        legacyFingerprint,
                        startedAt))
                .isEqualTo(1);
        assertThat(store.findOwned(legacyConversationId, "alice").orElseThrow().activeClaimAttemptId())
                .isNull();
        assertThat(legacyRelease(
                        legacyJdbc,
                        legacyConversationId,
                        0,
                        legacyClientTurnId,
                        legacyFingerprint,
                        startedAt.plusSeconds(1)))
                .isEqualTo(1);

        UUID claimedConversationId = UUID.randomUUID();
        UUID claimedClientTurnId = UUID.randomUUID();
        UUID claimAttemptId = UUID.randomUUID();
        String claimedFingerprint = "6".repeat(64);
        store.createNew(claimedConversationId, "alice", state(List.of()), startedAt);
        assertThat(store.claimTurn(
                        claimedConversationId,
                        "alice",
                        0,
                        claimedClientTurnId,
                        claimedFingerprint,
                        claimAttemptId,
                        startedAt,
                        startedAt.minusSeconds(60)))
                .isTrue();
        ConversationState completedState = state(List.of(
                new DialogueMessage("user", "legacy-compatible completion"),
                new DialogueMessage("assistant", "completed")));
        ConversationResponse completedResponse = response("completed");

        assertThat(legacyComplete(
                        legacyJdbc,
                        claimedConversationId,
                        claimedClientTurnId,
                        claimedFingerprint,
                        completedState,
                        completedResponse,
                        startedAt.plusSeconds(2)))
                .isEqualTo(1);
        var completed = store.findOwned(claimedConversationId, "alice").orElseThrow();
        assertThat(completed.revision()).isEqualTo(1);
        assertThat(completed.activeClientTurnId()).isNull();
        assertThat(completed.activeClaimAttemptId()).isEqualTo(claimAttemptId);
        assertThat(completed.lastResponse()).isEqualTo(completedResponse);

        UUID legacyNextClientTurnId = UUID.randomUUID();
        String legacyNextFingerprint = "4".repeat(64);
        assertThat(legacyClaim(
                        legacyJdbc,
                        claimedConversationId,
                        1,
                        legacyNextClientTurnId,
                        legacyNextFingerprint,
                        startedAt.plusSeconds(3)))
                .isEqualTo(1);
        assertThat(store.findOwned(claimedConversationId, "alice").orElseThrow().activeClaimAttemptId())
                .isEqualTo(claimAttemptId);
        assertThat(legacyRelease(
                        legacyJdbc,
                        claimedConversationId,
                        1,
                        legacyNextClientTurnId,
                        legacyNextFingerprint,
                        startedAt.plusSeconds(4)))
                .isEqualTo(1);

        UUID nextClientTurnId = UUID.randomUUID();
        UUID nextAttemptId = UUID.randomUUID();
        String nextFingerprint = "5".repeat(64);
        assertThat(store.claimTurn(
                        claimedConversationId,
                        "alice",
                        1,
                        nextClientTurnId,
                        nextFingerprint,
                        nextAttemptId,
                        startedAt.plusSeconds(5),
                        startedAt.minusSeconds(60)))
                .isTrue();
        assertThat(legacyRelease(
                        legacyJdbc,
                        claimedConversationId,
                        1,
                        nextClientTurnId,
                        nextFingerprint,
                        startedAt.plusSeconds(6)))
                .isEqualTo(1);
        var released = store.findOwned(claimedConversationId, "alice").orElseThrow();
        assertThat(released.activeClientTurnId()).isNull();
        assertThat(released.activeClaimAttemptId()).isEqualTo(nextAttemptId);
    }

    @Test
    void atomicallyClaimsCompletesAndRestoresAFullResponseAcrossRepositoryInstances() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        UUID claimAttemptId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-08-15T08:00:00Z");
        ConversationState initial = state(List.of());
        var created = store.createIfAbsent(conversationId, "alice", initial, startedAt);

        assertThat(created.revision()).isZero();
        assertThat(store.findOwned(conversationId, "bob")).isEmpty();
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        "a".repeat(64),
                        claimAttemptId,
                        startedAt,
                        startedAt.minusSeconds(60)))
                .isTrue();

        ConversationResponse response = response("我记住了这组条件。");
        ConversationState completedState = new ConversationState(
                RecommendationProfile.empty(),
                List.of(
                        new DialogueMessage("user", "四个人，九十分钟"),
                        new DialogueMessage("assistant", "我记住了这组条件。")),
                List.of(),
                List.of(301),
                List.of(response.games().getFirst().game()));
        assertThat(store.completeTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        "a".repeat(64),
                        claimAttemptId,
                        completedState,
                        response,
                        "zh-CN",
                        startedAt.plusSeconds(2)))
                .isTrue();

        PostgresRecommendationConversationStore restarted = new PostgresRecommendationConversationStore(
                jdbc,
                new ObjectMapper().findAndRegisterModules());
        var restored = restarted.findOwned(conversationId, "alice").orElseThrow();
        assertThat(restored.revision()).isEqualTo(1);
        assertThat(restored.state()).isEqualTo(completedState);
        assertThat(restored.state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(301);
        assertThat(restored.lastClientTurnId()).isEqualTo(clientTurnId);
        assertThat(restored.lastResponse()).isEqualTo(response);
        assertThat(restored.lastResponseLocale()).isEqualTo("zh-CN");
        assertThat(restored.activeClientTurnId()).isNull();
        assertThat(restored.activeClaimAttemptId()).isNull();
        assertThat(restored.updatedAt()).isEqualTo(startedAt.plusSeconds(2));
    }

    @Test
    void onlyOneConcurrentClientTurnCanClaimTheSameRevision() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-15T08:00:00Z");
        store.createIfAbsent(conversationId, "alice", state(List.of()), now);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> store.claimTurn(
                    conversationId, "alice", 0, UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(), now,
                    now.minusSeconds(60)));
            var second = executor.submit(() -> store.claimTurn(
                    conversationId, "alice", 0, UUID.randomUUID(), "c".repeat(64), UUID.randomUUID(), now,
                    now.minusSeconds(60)));

            assertThat(List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void checkpointsSettledReadStateWithoutCompletingOrReleasingThePlayerTurn() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-08-15T08:00:00Z");
        String fingerprint = "f".repeat(64);
        UUID claimAttemptId = UUID.randomUUID();
        store.createIfAbsent(conversationId, "alice", state(List.of()), startedAt);
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        claimAttemptId,
                        startedAt,
                        startedAt.minusSeconds(60)))
                .isTrue();
        Game verified = response("unused").games().getFirst().game();
        ConversationState checkpoint = new ConversationState(
                RecommendationProfile.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(verified));

        assertThat(store.checkpointTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        claimAttemptId,
                        checkpoint,
                        startedAt.plusSeconds(1)))
                .isTrue();

        var restored = store.findOwned(conversationId, "alice").orElseThrow();
        assertThat(restored.revision()).isZero();
        assertThat(restored.activeClientTurnId()).isEqualTo(clientTurnId);
        assertThat(restored.activeRequestFingerprint()).isEqualTo(fingerprint);
        assertThat(restored.activeClaimAttemptId()).isEqualTo(claimAttemptId);
        assertThat(restored.state().verifiedGames())
                .extracting(game -> game.ranking().bggId())
                .containsExactly(301);
    }

    @Test
    void onlyTheSameIdAndFingerprintCanRecoverItsOwnStaleClaim() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        Instant firstStart = Instant.parse("2026-08-15T08:00:00Z");
        String fingerprint = "d".repeat(64);
        UUID originalAttemptId = UUID.randomUUID();
        UUID recoveryAttemptId = UUID.randomUUID();
        store.createIfAbsent(conversationId, "alice", state(List.of()), firstStart);
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        originalAttemptId,
                        firstStart,
                        firstStart.minusSeconds(60)))
                .isTrue();

        Instant recovery = firstStart.plusSeconds(120);
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        UUID.randomUUID(),
                        "e".repeat(64),
                        UUID.randomUUID(),
                        recovery,
                        recovery.minusSeconds(60)))
                .isFalse();
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        recoveryAttemptId,
                        recovery,
                        recovery.minusSeconds(60)))
                .isTrue();
        assertThat(store.findOwned(conversationId, "alice").orElseThrow().activeClaimAttemptId())
                .isEqualTo(recoveryAttemptId)
                .isNotEqualTo(originalAttemptId);
    }

    @Test
    void staleTakeoverFencesEveryMutationFromThePreviousClaimAttempt() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        UUID staleAttemptId = UUID.randomUUID();
        UUID recoveryAttemptId = UUID.randomUUID();
        UUID finalAttemptId = UUID.randomUUID();
        String fingerprint = "9".repeat(64);
        Instant firstStart = Instant.parse("2026-08-15T08:00:00Z");
        Instant recovery = firstStart.plusSeconds(120);
        ConversationState initial = state(List.of());
        ConversationState staleCheckpoint = state(List.of(
                new DialogueMessage("assistant", "stale checkpoint")));
        ConversationState recoveredCheckpoint = state(List.of(
                new DialogueMessage("assistant", "recovered checkpoint")));
        ConversationState staleCompletion = state(List.of(
                new DialogueMessage("assistant", "stale completion")));
        ConversationState finalState = state(List.of(
                new DialogueMessage("assistant", "winning completion")));
        store.createIfAbsent(conversationId, "alice", initial, firstStart);
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        staleAttemptId,
                        firstStart,
                        firstStart.minusSeconds(60)))
                .isTrue();
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        recoveryAttemptId,
                        recovery,
                        recovery.minusSeconds(60)))
                .isTrue();

        assertThat(store.checkpointTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        staleAttemptId,
                        staleCheckpoint,
                        recovery.plusSeconds(1)))
                .isFalse();
        assertThat(store.checkpointTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        recoveryAttemptId,
                        recoveredCheckpoint,
                        recovery.plusSeconds(2)))
                .isTrue();

        store.releaseTurn(
                conversationId,
                "alice",
                0,
                clientTurnId,
                fingerprint,
                staleAttemptId,
                recovery.plusSeconds(3));
        assertThat(store.completeTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        staleAttemptId,
                        staleCompletion,
                        response("obsolete response"),
                        "en",
                        recovery.plusSeconds(4)))
                .isFalse();

        var stillRecovered = store.findOwned(conversationId, "alice").orElseThrow();
        assertThat(stillRecovered.revision()).isZero();
        assertThat(stillRecovered.state()).isEqualTo(recoveredCheckpoint);
        assertThat(stillRecovered.activeClaimAttemptId()).isEqualTo(recoveryAttemptId);
        assertThat(stillRecovered.updatedAt()).isEqualTo(recovery.plusSeconds(2));

        store.releaseTurn(
                conversationId,
                "alice",
                0,
                clientTurnId,
                fingerprint,
                recoveryAttemptId,
                recovery.plusSeconds(5));
        assertThat(store.findOwned(conversationId, "alice").orElseThrow().activeClientTurnId()).isNull();
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        finalAttemptId,
                        recovery.plusSeconds(6),
                        recovery.minusSeconds(60)))
                .isTrue();
        assertThat(store.completeTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        finalAttemptId,
                        finalState,
                        response("winning response"),
                        "en",
                        recovery.plusSeconds(7)))
                .isTrue();

        var completed = store.findOwned(conversationId, "alice").orElseThrow();
        assertThat(completed.revision()).isEqualTo(1);
        assertThat(completed.state()).isEqualTo(finalState);
        assertThat(completed.lastResponse().assistantMessage()).isEqualTo("winning response");
        assertThat(completed.activeClaimAttemptId()).isNull();
    }

    @Test
    void createsAtMostOneConversationPerOwnerAndDeletesOnlyWithMatchingOwnership() {
        Instant now = Instant.parse("2026-08-15T08:00:00Z");
        UUID originalId = UUID.randomUUID();
        var original = store.createIfAbsent(originalId, "alice", state(List.of()), now);
        var reused = store.createIfAbsent(UUID.randomUUID(), "alice", state(List.of()), now.plusSeconds(1));

        assertThat(reused.id()).isEqualTo(original.id());
        assertThat(store.deleteOwned(originalId, "bob")).isFalse();
        assertThat(store.findOwned(originalId, "alice")).isPresent();
        assertThat(store.deleteOwned(originalId, "alice")).isTrue();
        assertThat(store.findOwned(originalId, "alice")).isEmpty();
    }

    private static ConversationState state(List<DialogueMessage> transcript) {
        return new ConversationState(RecommendationProfile.empty(), transcript, List.of(), List.of());
    }

    private static ConversationResponse response(String message) {
        CandidateObservation observation = new CandidateObservation(
                "B301:playerCount",
                301,
                CandidateObservation.Kind.STRUCTURED_METADATA,
                "playerCount",
                "2..4",
                List.of());
        CandidateClaim claim = new CandidateClaim(
                301,
                "playerCount",
                CandidateClaim.Type.CONSTRAINT_FIT,
                ConstraintRange.Strength.HARD,
                CandidateClaim.Relation.SATISFIED,
                "The listed range supports four players.",
                List.of(observation));
        Game game = new Game(
                new Ranking(
                        301,
                        "Opaque Candidate",
                        2025,
                        null,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        400,
                        List.of()),
                new Details(
                        "Opaque Candidate",
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.3"),
                        List.of("Abstract Strategy"),
                        List.of("Pattern Building"),
                        45,
                        60,
                        10,
                        10,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()));
        return new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                message,
                RecommendationProfile.empty(),
                null,
                10,
                0,
                List.of(new RecommendedGame(game, List.of(), List.of(), List.of(), List.of(claim))));
    }

    private static int legacyClaim(
            JdbcTemplate legacyJdbc,
            UUID conversationId,
            long expectedRevision,
            UUID clientTurnId,
            String fingerprint,
            Instant startedAt) {
        return legacyJdbc.update(
                """
                update recommendation_conversation
                set active_client_turn_id = ?,
                    active_request_fingerprint = ?,
                    active_started_at = ?,
                    updated_at = ?
                where id = ?
                  and owner_username = 'alice'
                  and revision = ?
                  and (
                    active_client_turn_id is null
                    or (
                      active_client_turn_id = ?
                      and active_request_fingerprint = ?
                      and active_started_at <= ?
                    )
                  )
                """,
                clientTurnId,
                fingerprint,
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                conversationId,
                expectedRevision,
                clientTurnId,
                fingerprint,
                Timestamp.from(startedAt.minusSeconds(60)));
    }

    private static int legacyComplete(
            JdbcTemplate legacyJdbc,
            UUID conversationId,
            UUID clientTurnId,
            String fingerprint,
            ConversationState completedState,
            ConversationResponse response,
            Instant completedAt) {
        return legacyJdbc.update(
                """
                update recommendation_conversation
                set revision = revision + 1,
                    state_json = cast(? as jsonb),
                    last_client_turn_id = ?,
                    last_request_fingerprint = ?,
                    last_response_json = cast(? as jsonb),
                    last_response_locale = 'en',
                    active_client_turn_id = null,
                    active_request_fingerprint = null,
                    active_started_at = null,
                    updated_at = ?
                where id = ?
                  and owner_username = 'alice'
                  and revision = 0
                  and active_client_turn_id = ?
                  and active_request_fingerprint = ?
                """,
                write(completedState),
                clientTurnId,
                fingerprint,
                write(response),
                Timestamp.from(completedAt),
                conversationId,
                clientTurnId,
                fingerprint);
    }

    private static int legacyRelease(
            JdbcTemplate legacyJdbc,
            UUID conversationId,
            long expectedRevision,
            UUID clientTurnId,
            String fingerprint,
            Instant releasedAt) {
        return legacyJdbc.update(
                """
                update recommendation_conversation
                set active_client_turn_id = null,
                    active_request_fingerprint = null,
                    active_started_at = null,
                    updated_at = ?
                where id = ?
                  and owner_username = 'alice'
                  and revision = ?
                  and active_client_turn_id = ?
                  and active_request_fingerprint = ?
                """,
                Timestamp.from(releasedAt),
                conversationId,
                expectedRevision,
                clientTurnId,
                fingerprint);
    }

    private static String write(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize recommendation migration fixture", exception);
        }
    }

    private static void enableProductionExtensions() {
        enableProductionExtensions(POSTGRES);
    }

    private static void enableProductionExtensions(PostgreSQLContainer<?> postgres) {
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the production PostgreSQL extensions", exception);
        }
    }
}
