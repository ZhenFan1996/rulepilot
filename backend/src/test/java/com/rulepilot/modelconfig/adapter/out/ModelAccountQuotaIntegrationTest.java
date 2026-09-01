package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.modelconfig.AccountQuotaExceededException;
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.ModelConfigurationStore;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ModelAccountQuotaIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
            .withDatabaseName("rulepilot")
            .withUsername("rulepilot")
            .withPassword("rulepilot-test");

    @BeforeAll
    static void migrate() {
        enableProductionExtensions();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var jdbc = jdbc();
        jdbc.getJdbcTemplate().update(
                "INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, TRUE)",
                "admin",
                "unused");
        jdbc.getJdbcTemplate().update(
                "INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, TRUE)",
                "alice",
                "unused");
    }

    @Test
    void reservesPlatformQuotaAtomicallyWhilePersonalUsageDoesNotConsumeIt() {
        var quota = quota();
        Instant now = Instant.parse("2026-08-21T08:00:00Z");
        quota.updateLimit("alice", true, 100, "admin", now);

        var platform = quota.reserve(request(ModelAccountQuota.CredentialSource.PLATFORM, 60, now));
        assertThatThrownBy(() -> quota.reserve(request(ModelAccountQuota.CredentialSource.PLATFORM, 50, now)))
                .isInstanceOf(AccountQuotaExceededException.class)
                .hasMessage("ACCOUNT_QUOTA_EXHAUSTED");

        var personal = quota.reserve(request(ModelAccountQuota.CredentialSource.PERSONAL, 500, now));
        quota.settle(personal.id(), new ModelAccountQuota.Usage(20, 30, "SUCCESS"), now.plusSeconds(2));
        quota.settle(platform.id(), new ModelAccountQuota.Usage(30, 20, "SUCCESS"), now.plusSeconds(3));

        var finalReservation = quota.reserve(request(ModelAccountQuota.CredentialSource.PLATFORM, 50, now.plusSeconds(4)));
        ModelAccountQuota.AccountUsage usage = quota.usage("alice", LocalDate.of(2026, 8, 1));

        assertThat(usage.platformTokensCharged()).isEqualTo(50);
        assertThat(usage.platformTokensReserved()).isEqualTo(50);
        assertThat(usage.platformTokensRemaining()).isZero();
        assertThat(usage.personalTokensUsed()).isEqualTo(50);
        quota.release(finalReservation.id(), "CANCELLED", now.plusSeconds(5));
        assertThat(quota.usage("alice", LocalDate.of(2026, 8, 1)).platformTokensRemaining())
                .isEqualTo(50);
    }

    @Test
    void disabledPlatformAccessStillAllowsPersonalCredentials() {
        var quota = quota();
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        quota.updateLimit("alice", false, 1_000, "admin", now);

        assertThatThrownBy(() -> quota.reserve(request(ModelAccountQuota.CredentialSource.PLATFORM, 1, now)))
                .isInstanceOf(AccountQuotaExceededException.class);
        assertThat(quota.reserve(request(ModelAccountQuota.CredentialSource.PERSONAL, 1_000, now)))
                .isNotNull();
    }

    @Test
    void persistsEncryptedPersonalAndPlatformConfigurationWithoutSecretsInTheAuditLog() {
        var store = new PostgresModelConfigurationStore(jdbc());
        var cipher = new AesGcmModelCredentialCipher(new byte[32], (short) 1, new SecureRandom());
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        var personalSecret = cipher.encrypt("PERSONAL|alice|deepseek", "personal-secret");
        var platformSecret = cipher.encrypt("PLATFORM|qwen", "platform-secret");

        store.savePersonalProvider(
                "alice",
                new ModelConfigurationStore.StoredProvider(
                        "deepseek", personalSecret, "https://api.deepseek.com", "deepseek-chat", false, 0),
                now);
        store.savePersonalAssignments(
                "alice",
                new ModelConfigurationStore.StoredAssignments(
                        "deepseek", "fake", "deepseek", "fake", "deepseek", 0),
                now);
        store.savePlatformProvider(
                "admin",
                new ModelConfigurationStore.StoredProvider(
                        "qwen",
                        platformSecret,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus",
                        false,
                        0),
                now);

        var personal = store.personal("alice").orElseThrow();
        var platform = store.platform().orElseThrow();
        assertThat(cipher.decrypt(
                        "PERSONAL|alice|deepseek", personal.providers().getFirst().encryptedApiKey()))
                .isEqualTo("personal-secret");
        assertThat(cipher.decrypt("PLATFORM|qwen", platform.providers().getFirst().encryptedApiKey()))
                .isEqualTo("platform-secret");
        assertThat(personal.assignments().recommendation()).isEqualTo("deepseek");
        assertThat(jdbc().getJdbcTemplate().queryForList(
                        "SELECT actor_username || ':' || action FROM model_configuration_audit ORDER BY occurred_at",
                        String.class))
                .hasSize(3)
                .allSatisfy(event -> assertThat(event)
                        .doesNotContain("personal-secret")
                        .doesNotContain("platform-secret"));
    }

    @Test
    void reclaimsAnExpiredPlatformReservationButKeepsAFreshReservationAtomic() {
        var quota = quota();
        Instant reservedAt = Instant.parse("2026-12-01T00:00:00Z");
        quota.updateLimit("alice", true, 16_000, "admin", reservedAt);
        var abandoned = quota.reserve(request(ModelAccountQuota.CredentialSource.PLATFORM, 16_000, reservedAt));

        assertThatThrownBy(() -> quota.reserve(request(
                        ModelAccountQuota.CredentialSource.PLATFORM,
                        16_000,
                        reservedAt.plus(Duration.ofMinutes(14)))))
                .isInstanceOf(AccountQuotaExceededException.class);

        Instant recoveredAt = reservedAt.plus(Duration.ofMinutes(16));
        var recovered = quota.reserve(request(
                ModelAccountQuota.CredentialSource.PLATFORM, 16_000, recoveredAt));
        var expiredRow = jdbc().getJdbcTemplate().queryForMap(
                "SELECT status, outcome, settled_at FROM model_usage_ledger WHERE reservation_id = ?",
                abandoned.id());

        assertThat(expiredRow)
                .containsEntry("status", "RELEASED")
                .containsEntry("outcome", "RESERVATION_EXPIRED");
        assertThat(expiredRow.get("settled_at")).isNotNull();
        assertThat(quota.usage("alice", LocalDate.of(2026, 12, 1)).platformTokensReserved())
                .isEqualTo(16_000);
        quota.release(recovered.id(), "CANCELLED", recoveredAt.plusSeconds(1));
    }

    private static ModelAccountQuota.Request request(
            ModelAccountQuota.CredentialSource source, long reservedTokens, Instant now) {
        return new ModelAccountQuota.Request(
                "alice",
                source,
                RuntimeModelConfiguration.Role.RECOMMENDATION,
                "qwen",
                "qwen3.7-plus",
                "recommendation.next",
                reservedTokens,
                now);
    }

    private static NamedParameterJdbcTemplate jdbc() {
        return new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static PostgresModelAccountQuota quota() {
        return new PostgresModelAccountQuota(
                jdbc(), 200_000, Duration.ofMinutes(2), Duration.ofMinutes(15));
    }

    private static void enableProductionExtensions() {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the production PostgreSQL extensions", exception);
        }
    }
}
