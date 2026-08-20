package com.rulepilot.modelconfig.adapter.out;

import com.rulepilot.modelconfig.AccountQuotaExceededException;
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnBean(NamedParameterJdbcTemplate.class)
public class PostgresModelAccountQuota implements ModelAccountQuota {

    private final NamedParameterJdbcTemplate jdbc;
    private final long defaultMonthlyTokenLimit;

    public PostgresModelAccountQuota(
            NamedParameterJdbcTemplate jdbc,
            @Value("${rulepilot.models.default-monthly-token-limit:200000}") long defaultMonthlyTokenLimit) {
        if (defaultMonthlyTokenLimit < 0) throw new IllegalArgumentException("Default model quota cannot be negative");
        this.jdbc = jdbc;
        this.defaultMonthlyTokenLimit = defaultMonthlyTokenLimit;
    }

    @Override
    @Transactional
    public Reservation reserve(Request request) {
        CheckedRequest checked = checked(request);
        ensureQuotaRow(checked.username(), checked.requestedAt());
        if (checked.credentialSource() == CredentialSource.PLATFORM) {
            Quota quota = lockQuota(checked.username());
            Totals totals = totals(checked.username(), checked.periodStart());
            if (!quota.platformAccessEnabled()
                    || checked.reservedTokens() > quota.monthlyTokenLimit() - totals.charged() - totals.reserved()) {
                throw new AccountQuotaExceededException();
            }
        }
        UUID reservationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO model_usage_ledger (
                    reservation_id, username, credential_source, role, provider, model_name,
                    operation, period_start, reserved_tokens, charged_tokens, status, created_at)
                VALUES (
                    :reservationId, :username, :credentialSource, :role, :provider, :model,
                    :operation, :periodStart, :reservedTokens, 0, 'RESERVED', :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("username", checked.username())
                        .addValue("credentialSource", checked.credentialSource().name())
                        .addValue("role", checked.role().name())
                        .addValue("provider", checked.provider())
                        .addValue("model", checked.model())
                        .addValue("operation", checked.operation())
                        .addValue("periodStart", Date.valueOf(checked.periodStart()))
                        .addValue("reservedTokens", checked.reservedTokens())
                        .addValue("createdAt", Timestamp.from(checked.requestedAt())));
        return new Reservation(reservationId, checked.credentialSource(), checked.reservedTokens());
    }

    @Override
    public void settle(UUID reservationId, Usage usage, Instant settledAt) {
        if (reservationId == null || usage == null || settledAt == null
                || usage.promptTokens() < 0 || usage.completionTokens() < 0) {
            throw new IllegalArgumentException("Model usage settlement is invalid");
        }
        long total = Math.addExact(usage.promptTokens(), usage.completionTokens());
        int changed = jdbc.update(
                """
                UPDATE model_usage_ledger
                SET prompt_tokens = :promptTokens,
                    completion_tokens = :completionTokens,
                    charged_tokens = CASE WHEN credential_source = 'PLATFORM' THEN :totalTokens ELSE 0 END,
                    status = 'SETTLED', outcome = :outcome, settled_at = :settledAt
                WHERE reservation_id = :reservationId AND status = 'RESERVED'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("promptTokens", usage.promptTokens())
                        .addValue("completionTokens", usage.completionTokens())
                        .addValue("totalTokens", total)
                        .addValue("outcome", bounded(usage.outcome(), "SUCCESS", 32))
                        .addValue("settledAt", Timestamp.from(settledAt)));
        if (changed != 1) throw new IllegalStateException("Model usage reservation is not open");
    }

    @Override
    public void release(UUID reservationId, String outcome, Instant releasedAt) {
        if (reservationId == null || releasedAt == null) {
            throw new IllegalArgumentException("Model usage release is invalid");
        }
        int changed = jdbc.update(
                """
                UPDATE model_usage_ledger
                SET charged_tokens = 0, status = 'RELEASED', outcome = :outcome, settled_at = :settledAt
                WHERE reservation_id = :reservationId AND status = 'RESERVED'
                """,
                new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("outcome", bounded(outcome, "FAILED", 32))
                        .addValue("settledAt", Timestamp.from(releasedAt)));
        if (changed != 1) throw new IllegalStateException("Model usage reservation is not open");
    }

    @Override
    @Transactional(readOnly = true)
    public AccountUsage usage(String username, LocalDate periodStart) {
        String owner = bounded(username, null, 40);
        if (periodStart == null) throw new IllegalArgumentException("Model usage period is required");
        Quota quota = jdbc.query(
                        """
                        SELECT platform_access_enabled, monthly_token_limit, revision
                        FROM model_account_quota WHERE username = :username
                        """,
                        Map.of("username", owner),
                        result -> result.next()
                                ? new Quota(
                                        result.getBoolean("platform_access_enabled"),
                                        result.getLong("monthly_token_limit"),
                                        result.getLong("revision"))
                                : new Quota(true, defaultMonthlyTokenLimit, 0)) ;
        Totals totals = totals(owner, periodStart);
        return new AccountUsage(
                owner,
                quota.platformAccessEnabled(),
                quota.monthlyTokenLimit(),
                totals.charged(),
                totals.reserved(),
                totals.personal(),
                periodStart,
                quota.revision());
    }

    @Override
    @Transactional
    public AccountUsage updateLimit(
            String username,
            boolean platformAccessEnabled,
            long monthlyTokenLimit,
            String administrator,
            Instant updatedAt) {
        String owner = bounded(username, null, 40);
        String actor = bounded(administrator, null, 40);
        if (monthlyTokenLimit < 0 || updatedAt == null) {
            throw new IllegalArgumentException("Account model quota is invalid");
        }
        jdbc.update(
                """
                INSERT INTO model_account_quota (
                    username, platform_access_enabled, monthly_token_limit, revision, updated_by, updated_at)
                VALUES (:username, :enabled, :limit, 1, :actor, :updatedAt)
                ON CONFLICT (username) DO UPDATE SET
                    platform_access_enabled = EXCLUDED.platform_access_enabled,
                    monthly_token_limit = EXCLUDED.monthly_token_limit,
                    revision = model_account_quota.revision + 1,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("username", owner)
                        .addValue("enabled", platformAccessEnabled)
                        .addValue("limit", monthlyTokenLimit)
                        .addValue("actor", actor)
                        .addValue("updatedAt", Timestamp.from(updatedAt)));
        return usage(owner, periodStart(updatedAt));
    }

    private void ensureQuotaRow(String username, Instant now) {
        jdbc.update(
                """
                INSERT INTO model_account_quota (
                    username, platform_access_enabled, monthly_token_limit, revision, updated_by, updated_at)
                VALUES (:username, TRUE, :limit, 1, :username, :updatedAt)
                ON CONFLICT (username) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("username", username)
                        .addValue("limit", defaultMonthlyTokenLimit)
                        .addValue("updatedAt", Timestamp.from(now)));
    }

    private Quota lockQuota(String username) {
        return jdbc.query(
                """
                SELECT platform_access_enabled, monthly_token_limit, revision
                FROM model_account_quota WHERE username = :username FOR UPDATE
                """,
                Map.of("username", username),
                result -> {
                    if (!result.next()) throw new IllegalStateException("Account model quota is missing");
                    return new Quota(
                            result.getBoolean("platform_access_enabled"),
                            result.getLong("monthly_token_limit"),
                            result.getLong("revision"));
                });
    }

    private Totals totals(String username, LocalDate periodStart) {
        return jdbc.query(
                """
                SELECT
                    COALESCE(SUM(charged_tokens) FILTER (
                        WHERE credential_source = 'PLATFORM' AND status = 'SETTLED'), 0) AS charged,
                    COALESCE(SUM(reserved_tokens) FILTER (
                        WHERE credential_source = 'PLATFORM' AND status = 'RESERVED'), 0) AS reserved,
                    COALESCE(SUM(COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0)) FILTER (
                        WHERE credential_source = 'PERSONAL' AND status = 'SETTLED'), 0) AS personal
                FROM model_usage_ledger
                WHERE username = :username AND period_start = :periodStart
                """,
                new MapSqlParameterSource()
                        .addValue("username", username)
                        .addValue("periodStart", Date.valueOf(periodStart)),
                result -> {
                    result.next();
                    return new Totals(
                            result.getLong("charged"),
                            result.getLong("reserved"),
                            result.getLong("personal"));
                });
    }

    private CheckedRequest checked(Request request) {
        if (request == null || request.credentialSource() == null || request.role() == null
                || request.requestedAt() == null || request.reservedTokens() < 1) {
            throw new IllegalArgumentException("Model usage reservation is invalid");
        }
        return new CheckedRequest(
                bounded(request.username(), null, 40),
                request.credentialSource(),
                request.role(),
                bounded(request.provider(), null, 40).toLowerCase(Locale.ROOT),
                bounded(request.model(), null, 200),
                bounded(request.operation(), request.role().name(), 120),
                request.reservedTokens(),
                request.requestedAt(),
                periodStart(request.requestedAt()));
    }

    private String bounded(String value, String fallback, int maxLength) {
        String checked = value == null || value.isBlank() ? fallback : value.strip();
        if (checked == null || checked.isBlank() || checked.length() > maxLength) {
            throw new IllegalArgumentException("Model usage field is invalid");
        }
        return checked;
    }

    private LocalDate periodStart(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
        return date.withDayOfMonth(1);
    }

    private record CheckedRequest(
            String username,
            CredentialSource credentialSource,
            RuntimeModelConfiguration.Role role,
            String provider,
            String model,
            String operation,
            long reservedTokens,
            Instant requestedAt,
            LocalDate periodStart) {}

    private record Quota(boolean platformAccessEnabled, long monthlyTokenLimit, long revision) {}

    private record Totals(long charged, long reserved, long personal) {}
}
