package com.rulepilot.modelconfig.adapter.out;

import com.rulepilot.modelconfig.ModelConfigurationStore;
import com.rulepilot.modelconfig.ModelCredentialCipher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnBean(NamedParameterJdbcTemplate.class)
public class PostgresModelConfigurationStore implements ModelConfigurationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresModelConfigurationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredConfiguration> personal(String username) {
        List<StoredProvider> providers = providers(
                """
                SELECT provider, encrypted_api_key, encryption_nonce, encryption_key_version,
                       base_url, model_name, vision_capable, revision
                FROM model_personal_provider WHERE username = :username ORDER BY provider
                """,
                Map.of("username", username));
        Optional<StoredAssignments> assignments = assignments(
                """
                SELECT teaching_provider, visual_provider, answer_provider, critic_provider,
                       recommendation_provider, revision
                FROM model_personal_assignment WHERE username = :username
                """,
                Map.of("username", username));
        if (providers.isEmpty() && assignments.isEmpty()) return Optional.empty();
        long revision = assignments.map(StoredAssignments::revision)
                .orElseGet(() -> providers.stream().mapToLong(StoredProvider::revision).max().orElse(0));
        return Optional.of(new StoredConfiguration(providers, assignments.orElse(null), revision));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredConfiguration> platform() {
        List<StoredProvider> providers = providers(
                """
                SELECT provider, encrypted_api_key, encryption_nonce, encryption_key_version,
                       base_url, model_name, vision_capable, revision
                FROM model_platform_provider ORDER BY provider
                """,
                Map.of());
        Optional<StoredAssignments> assignments = assignments(
                """
                SELECT teaching_provider, visual_provider, answer_provider, critic_provider,
                       recommendation_provider, revision
                FROM model_platform_assignment WHERE singleton
                """,
                Map.of());
        if (providers.isEmpty() && assignments.isEmpty()) return Optional.empty();
        long revision = assignments.map(StoredAssignments::revision)
                .orElseGet(() -> providers.stream().mapToLong(StoredProvider::revision).max().orElse(0));
        return Optional.of(new StoredConfiguration(providers, assignments.orElse(null), revision));
    }

    @Override
    @Transactional
    public long savePersonalProvider(String username, StoredProvider provider, Instant updatedAt) {
        long revision = jdbc.queryForObject(
                """
                INSERT INTO model_personal_provider (
                    username, provider, encrypted_api_key, encryption_nonce, encryption_key_version,
                    base_url, model_name, vision_capable, revision, created_at, updated_at)
                VALUES (
                    :username, :provider, :ciphertext, :nonce, :keyVersion,
                    :baseUrl, :model, :visionCapable, 1, :updatedAt, :updatedAt)
                ON CONFLICT (username, provider) DO UPDATE SET
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    encryption_nonce = EXCLUDED.encryption_nonce,
                    encryption_key_version = EXCLUDED.encryption_key_version,
                    base_url = EXCLUDED.base_url,
                    model_name = EXCLUDED.model_name,
                    vision_capable = EXCLUDED.vision_capable,
                    revision = model_personal_provider.revision + 1,
                    updated_at = EXCLUDED.updated_at
                RETURNING revision
                """,
                providerParameters(provider, updatedAt).addValue("username", username),
                Long.class);
        audit(username, "PERSONAL", username, provider.provider(), "PROVIDER_SAVED", revision, updatedAt);
        return revision;
    }

    @Override
    @Transactional
    public long removePersonalProvider(String username, String provider, Instant updatedAt) {
        long revision = nextPersonalRevision(username);
        jdbc.update(
                "DELETE FROM model_personal_provider WHERE username = :username AND provider = :provider",
                Map.of("username", username, "provider", provider));
        audit(username, "PERSONAL", username, provider, "PROVIDER_REMOVED", revision, updatedAt);
        return revision;
    }

    @Override
    @Transactional
    public long savePersonalAssignments(String username, StoredAssignments assignments, Instant updatedAt) {
        long revision = jdbc.queryForObject(
                """
                INSERT INTO model_personal_assignment (
                    username, teaching_provider, visual_provider, answer_provider, critic_provider,
                    recommendation_provider, revision, updated_at)
                VALUES (
                    :username, :teaching, :visual, :answer, :critic, :recommendation, 1, :updatedAt)
                ON CONFLICT (username) DO UPDATE SET
                    teaching_provider = EXCLUDED.teaching_provider,
                    visual_provider = EXCLUDED.visual_provider,
                    answer_provider = EXCLUDED.answer_provider,
                    critic_provider = EXCLUDED.critic_provider,
                    recommendation_provider = EXCLUDED.recommendation_provider,
                    revision = model_personal_assignment.revision + 1,
                    updated_at = EXCLUDED.updated_at
                RETURNING revision
                """,
                assignmentParameters(assignments, updatedAt).addValue("username", username),
                Long.class);
        audit(username, "PERSONAL", username, null, "ASSIGNMENTS_SAVED", revision, updatedAt);
        return revision;
    }

    @Override
    @Transactional
    public long savePlatformProvider(String administrator, StoredProvider provider, Instant updatedAt) {
        long revision = jdbc.queryForObject(
                """
                INSERT INTO model_platform_provider (
                    provider, encrypted_api_key, encryption_nonce, encryption_key_version,
                    base_url, model_name, vision_capable, revision, updated_by, created_at, updated_at)
                VALUES (
                    :provider, :ciphertext, :nonce, :keyVersion,
                    :baseUrl, :model, :visionCapable, 1, :actor, :updatedAt, :updatedAt)
                ON CONFLICT (provider) DO UPDATE SET
                    encrypted_api_key = EXCLUDED.encrypted_api_key,
                    encryption_nonce = EXCLUDED.encryption_nonce,
                    encryption_key_version = EXCLUDED.encryption_key_version,
                    base_url = EXCLUDED.base_url,
                    model_name = EXCLUDED.model_name,
                    vision_capable = EXCLUDED.vision_capable,
                    revision = model_platform_provider.revision + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                RETURNING revision
                """,
                providerParameters(provider, updatedAt).addValue("actor", administrator),
                Long.class);
        audit(administrator, "PLATFORM", null, provider.provider(), "PROVIDER_SAVED", revision, updatedAt);
        return revision;
    }

    @Override
    @Transactional
    public long removePlatformProvider(String administrator, String provider, Instant updatedAt) {
        long revision = nextPlatformRevision();
        jdbc.update("DELETE FROM model_platform_provider WHERE provider = :provider", Map.of("provider", provider));
        audit(administrator, "PLATFORM", null, provider, "PROVIDER_REMOVED", revision, updatedAt);
        return revision;
    }

    @Override
    @Transactional
    public long savePlatformAssignments(String administrator, StoredAssignments assignments, Instant updatedAt) {
        long revision = jdbc.queryForObject(
                """
                INSERT INTO model_platform_assignment (
                    singleton, teaching_provider, visual_provider, answer_provider, critic_provider,
                    recommendation_provider, revision, updated_by, updated_at)
                VALUES (
                    TRUE, :teaching, :visual, :answer, :critic, :recommendation, 1, :actor, :updatedAt)
                ON CONFLICT (singleton) DO UPDATE SET
                    teaching_provider = EXCLUDED.teaching_provider,
                    visual_provider = EXCLUDED.visual_provider,
                    answer_provider = EXCLUDED.answer_provider,
                    critic_provider = EXCLUDED.critic_provider,
                    recommendation_provider = EXCLUDED.recommendation_provider,
                    revision = model_platform_assignment.revision + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                RETURNING revision
                """,
                assignmentParameters(assignments, updatedAt).addValue("actor", administrator),
                Long.class);
        audit(administrator, "PLATFORM", null, null, "ASSIGNMENTS_SAVED", revision, updatedAt);
        return revision;
    }

    private List<StoredProvider> providers(String sql, Map<String, ?> parameters) {
        return jdbc.query(sql, parameters, (result, row) -> new StoredProvider(
                result.getString("provider"),
                new ModelCredentialCipher.EncryptedSecret(
                        result.getBytes("encrypted_api_key"),
                        result.getBytes("encryption_nonce"),
                        result.getShort("encryption_key_version")),
                result.getString("base_url"),
                result.getString("model_name"),
                result.getBoolean("vision_capable"),
                result.getLong("revision")));
    }

    private Optional<StoredAssignments> assignments(String sql, Map<String, ?> parameters) {
        return jdbc.query(sql, parameters, (result, row) -> new StoredAssignments(
                        result.getString("teaching_provider"),
                        result.getString("visual_provider"),
                        result.getString("answer_provider"),
                        result.getString("critic_provider"),
                        result.getString("recommendation_provider"),
                        result.getLong("revision")))
                .stream()
                .findFirst();
    }

    private MapSqlParameterSource providerParameters(StoredProvider provider, Instant updatedAt) {
        return new MapSqlParameterSource()
                .addValue("provider", provider.provider())
                .addValue("ciphertext", provider.encryptedApiKey().ciphertext())
                .addValue("nonce", provider.encryptedApiKey().nonce())
                .addValue("keyVersion", provider.encryptedApiKey().keyVersion())
                .addValue("baseUrl", provider.baseUrl())
                .addValue("model", provider.model())
                .addValue("visionCapable", provider.visionCapable())
                .addValue("updatedAt", Timestamp.from(updatedAt));
    }

    private MapSqlParameterSource assignmentParameters(StoredAssignments assignments, Instant updatedAt) {
        return new MapSqlParameterSource()
                .addValue("teaching", assignments.teaching())
                .addValue("visual", assignments.visual())
                .addValue("answer", assignments.answer())
                .addValue("critic", assignments.critic())
                .addValue("recommendation", assignments.recommendation())
                .addValue("updatedAt", Timestamp.from(updatedAt));
    }

    private long nextPersonalRevision(String username) {
        Long revision = jdbc.queryForObject(
                """
                SELECT GREATEST(
                    COALESCE((SELECT MAX(revision) FROM model_personal_provider WHERE username = :username), 0),
                    COALESCE((SELECT revision FROM model_personal_assignment WHERE username = :username), 0)) + 1
                """,
                Map.of("username", username),
                Long.class);
        return revision == null ? 1 : revision;
    }

    private long nextPlatformRevision() {
        Long revision = jdbc.queryForObject(
                """
                SELECT GREATEST(
                    COALESCE((SELECT MAX(revision) FROM model_platform_provider), 0),
                    COALESCE((SELECT revision FROM model_platform_assignment WHERE singleton), 0)) + 1
                """,
                Map.of(),
                Long.class);
        return revision == null ? 1 : revision;
    }

    private void audit(
            String actor,
            String scope,
            String target,
            String provider,
            String action,
            long revision,
            Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO model_configuration_audit (
                    id, actor_username, target_scope, target_username, provider,
                    action, resulting_revision, occurred_at)
                VALUES (:id, :actor, :scope, :target, :provider, :action, :revision, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("actor", actor)
                        .addValue("scope", scope)
                        .addValue("target", target)
                        .addValue("provider", provider)
                        .addValue("action", action)
                        .addValue("revision", revision)
                        .addValue("occurredAt", Timestamp.from(occurredAt)));
    }
}
