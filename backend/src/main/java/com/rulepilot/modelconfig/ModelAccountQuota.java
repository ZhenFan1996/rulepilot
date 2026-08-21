package com.rulepilot.modelconfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface ModelAccountQuota {

    enum CredentialSource {
        PLATFORM,
        PERSONAL
    }

    Reservation reserve(Request request);

    void settle(UUID reservationId, Usage usage, Instant settledAt);

    void release(UUID reservationId, String outcome, Instant releasedAt);

    AccountUsage usage(String username, LocalDate periodStart);

    AccountUsage updateLimit(
            String username,
            boolean platformAccessEnabled,
            long monthlyTokenLimit,
            String administrator,
            Instant updatedAt);

    record Request(
            String username,
            CredentialSource credentialSource,
            RuntimeModelConfiguration.Role role,
            String provider,
            String model,
            String operation,
            long reservedTokens,
            Instant requestedAt) {}

    record Reservation(UUID id, CredentialSource credentialSource, long reservedTokens) {}

    record Usage(long promptTokens, long completionTokens, String outcome) {}

    record AccountUsage(
            String username,
            boolean platformAccessEnabled,
            long monthlyTokenLimit,
            long platformTokensCharged,
            long platformTokensReserved,
            long personalTokensUsed,
            LocalDate periodStart,
            long revision) {

        @JsonProperty("platformTokensRemaining")
        public long platformTokensRemaining() {
            return Math.max(0, monthlyTokenLimit - platformTokensCharged - platformTokensReserved);
        }
    }
}
