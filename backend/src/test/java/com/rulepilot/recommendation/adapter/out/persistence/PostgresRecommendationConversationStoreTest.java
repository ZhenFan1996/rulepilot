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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
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
    void atomicallyClaimsCompletesAndRestoresAFullResponseAcrossRepositoryInstances() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
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
        assertThat(restored.updatedAt()).isEqualTo(startedAt.plusSeconds(2));
    }

    @Test
    void onlyOneConcurrentClientTurnCanClaimTheSameRevision() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-15T08:00:00Z");
        store.createIfAbsent(conversationId, "alice", state(List.of()), now);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> store.claimTurn(
                    conversationId, "alice", 0, UUID.randomUUID(), "b".repeat(64), now, now.minusSeconds(60)));
            var second = executor.submit(() -> store.claimTurn(
                    conversationId, "alice", 0, UUID.randomUUID(), "c".repeat(64), now, now.minusSeconds(60)));

            assertThat(List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void onlyTheSameIdAndFingerprintCanRecoverItsOwnStaleClaim() {
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        Instant firstStart = Instant.parse("2026-08-15T08:00:00Z");
        String fingerprint = "d".repeat(64);
        store.createIfAbsent(conversationId, "alice", state(List.of()), firstStart);
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
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
                        recovery,
                        recovery.minusSeconds(60)))
                .isFalse();
        assertThat(store.claimTurn(
                        conversationId,
                        "alice",
                        0,
                        clientTurnId,
                        fingerprint,
                        recovery,
                        recovery.minusSeconds(60)))
                .isTrue();
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

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the production PostgreSQL extensions", exception);
        }
    }
}
