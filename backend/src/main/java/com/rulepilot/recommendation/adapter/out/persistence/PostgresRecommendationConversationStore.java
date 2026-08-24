package com.rulepilot.recommendation.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.RecommendationConversationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class PostgresRecommendationConversationStore implements RecommendationConversationStore {

    private static final String SELECT_COLUMNS = """
            select id, owner_username, revision, state_json,
                   last_client_turn_id, last_request_fingerprint, last_response_json, last_response_locale,
                   active_client_turn_id, active_request_fingerprint, active_claim_attempt_id, active_started_at,
                   created_at, updated_at
            from recommendation_conversation
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresRecommendationConversationStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    @Transactional
    public StoredConversation createIfAbsent(
            UUID conversationId,
            String ownerUsername,
            ConversationState initialState,
            Instant now) {
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(:owner, 0)) is null",
                Map.of("owner", ownerUsername),
                Boolean.class);
        Optional<StoredConversation> existing = findLatestOwned(ownerUsername);
        if (existing.isPresent()) return existing.get();
        return createNew(conversationId, ownerUsername, initialState, now);
    }

    @Override
    @Transactional
    public StoredConversation createNew(
            UUID conversationId,
            String ownerUsername,
            ConversationState initialState,
            Instant now) {
        jdbc.update(
                """
                insert into recommendation_conversation (
                    id, owner_username, revision, state_json, created_at, updated_at
                ) values (
                    :id, :owner, 0, cast(:stateJson as jsonb), :now, :now
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", conversationId)
                        .addValue("owner", ownerUsername)
                        .addValue("stateJson", write(initialState))
                        .addValue("now", Timestamp.from(now)));
        return findOwned(conversationId, ownerUsername)
                .orElseThrow(() -> new IllegalStateException("recommendation conversation was not created"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredConversation> findOwned(UUID conversationId, String ownerUsername) {
        return one(
                SELECT_COLUMNS + " where id = :id and owner_username = :owner",
                Map.of("id", conversationId, "owner", ownerUsername));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredConversation> findLatestOwned(String ownerUsername) {
        return one(
                SELECT_COLUMNS + " where owner_username = :owner order by updated_at desc, id desc limit 1",
                Map.of("owner", ownerUsername));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredConversation> findRecentOwned(String ownerUsername, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("conversation page size is invalid");
        return jdbc.query(
                SELECT_COLUMNS + " where owner_username = :owner order by updated_at desc, id desc limit :limit",
                new MapSqlParameterSource().addValue("owner", ownerUsername).addValue("limit", limit),
                this::row);
    }

    @Override
    @Transactional
    public boolean claimTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
            Instant startedAt,
            Instant staleBefore) {
        int updated = jdbc.update(
                """
                update recommendation_conversation
                set active_client_turn_id = :clientTurnId,
                    active_request_fingerprint = :fingerprint,
                    active_claim_attempt_id = :claimAttemptId,
                    active_started_at = :startedAt,
                    updated_at = :startedAt
                where id = :id
                  and owner_username = :owner
                  and revision = :expectedRevision
                  and (
                    active_client_turn_id is null
                    or (
                      active_client_turn_id = :clientTurnId
                      and active_request_fingerprint = :fingerprint
                      and active_started_at <= :staleBefore
                    )
                  )
                """,
                new MapSqlParameterSource()
                        .addValue("id", conversationId)
                        .addValue("owner", ownerUsername)
                        .addValue("expectedRevision", expectedRevision)
                        .addValue("clientTurnId", clientTurnId)
                        .addValue("fingerprint", requestFingerprint)
                        .addValue("claimAttemptId", claimAttemptId)
                        .addValue("startedAt", Timestamp.from(startedAt))
                        .addValue("staleBefore", Timestamp.from(staleBefore)));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean checkpointTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
            ConversationState checkpointState,
            Instant checkpointedAt) {
        int updated = jdbc.update(
                """
                update recommendation_conversation
                set state_json = cast(:stateJson as jsonb),
                    updated_at = :checkpointedAt
                where id = :id
                  and owner_username = :owner
                  and revision = :expectedRevision
                  and active_client_turn_id = :clientTurnId
                  and active_request_fingerprint = :fingerprint
                  and active_claim_attempt_id = :claimAttemptId
                """,
                new MapSqlParameterSource()
                        .addValue("id", conversationId)
                        .addValue("owner", ownerUsername)
                        .addValue("expectedRevision", expectedRevision)
                        .addValue("clientTurnId", clientTurnId)
                        .addValue("fingerprint", requestFingerprint)
                        .addValue("claimAttemptId", claimAttemptId)
                        .addValue("stateJson", write(checkpointState))
                        .addValue("checkpointedAt", Timestamp.from(checkpointedAt)));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean completeTurn(
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
        int updated = jdbc.update(
                """
                update recommendation_conversation
                set revision = revision + 1,
                    state_json = cast(:stateJson as jsonb),
                    last_client_turn_id = :clientTurnId,
                    last_request_fingerprint = :fingerprint,
                    last_response_json = cast(:responseJson as jsonb),
                    last_response_locale = :responseLocale,
                    active_client_turn_id = null,
                    active_request_fingerprint = null,
                    active_claim_attempt_id = null,
                    active_started_at = null,
                    updated_at = :completedAt
                where id = :id
                  and owner_username = :owner
                  and revision = :expectedRevision
                  and active_client_turn_id = :clientTurnId
                  and active_request_fingerprint = :fingerprint
                  and active_claim_attempt_id = :claimAttemptId
                """,
                new MapSqlParameterSource()
                        .addValue("id", conversationId)
                        .addValue("owner", ownerUsername)
                        .addValue("expectedRevision", expectedRevision)
                        .addValue("clientTurnId", clientTurnId)
                        .addValue("fingerprint", requestFingerprint)
                        .addValue("claimAttemptId", claimAttemptId)
                        .addValue("stateJson", write(nextState))
                        .addValue("responseJson", write(response))
                        .addValue("responseLocale", responseLocale)
                        .addValue("completedAt", Timestamp.from(completedAt)));
        return updated == 1;
    }

    @Override
    @Transactional
    public void releaseTurn(
            UUID conversationId,
            String ownerUsername,
            long expectedRevision,
            UUID clientTurnId,
            String requestFingerprint,
            UUID claimAttemptId,
            Instant releasedAt) {
        jdbc.update(
                """
                update recommendation_conversation
                set active_client_turn_id = null,
                    active_request_fingerprint = null,
                    active_claim_attempt_id = null,
                    active_started_at = null,
                    updated_at = :releasedAt
                where id = :id
                  and owner_username = :owner
                  and revision = :expectedRevision
                  and active_client_turn_id = :clientTurnId
                  and active_request_fingerprint = :fingerprint
                  and active_claim_attempt_id = :claimAttemptId
                """,
                new MapSqlParameterSource()
                        .addValue("id", conversationId)
                        .addValue("owner", ownerUsername)
                        .addValue("expectedRevision", expectedRevision)
                        .addValue("clientTurnId", clientTurnId)
                        .addValue("fingerprint", requestFingerprint)
                        .addValue("claimAttemptId", claimAttemptId)
                        .addValue("releasedAt", Timestamp.from(releasedAt)));
    }

    @Override
    @Transactional
    public boolean deleteOwned(UUID conversationId, String ownerUsername) {
        return jdbc.update(
                        """
                        delete from recommendation_conversation
                        where id = :id and owner_username = :owner
                        """,
                        Map.of("id", conversationId, "owner", ownerUsername))
                == 1;
    }

    private Optional<StoredConversation> one(String sql, Map<String, ?> parameters) {
        List<StoredConversation> rows = jdbc.query(sql, parameters, this::row);
        return rows.stream().findFirst();
    }

    private StoredConversation row(ResultSet result, int rowNumber) throws SQLException {
        return new StoredConversation(
                result.getObject("id", UUID.class),
                result.getString("owner_username"),
                result.getLong("revision"),
                read(result.getString("state_json"), ConversationState.class),
                result.getObject("last_client_turn_id", UUID.class),
                result.getString("last_request_fingerprint"),
                readNullable(result.getString("last_response_json"), ConversationResponse.class),
                result.getString("last_response_locale"),
                result.getObject("active_client_turn_id", UUID.class),
                result.getString("active_request_fingerprint"),
                result.getObject("active_claim_attempt_id", UUID.class),
                instant(result, "active_started_at"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation conversation could not be serialized", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation conversation could not be deserialized", exception);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }
}
