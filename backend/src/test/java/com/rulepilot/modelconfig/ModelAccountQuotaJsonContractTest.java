package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ModelAccountQuotaJsonContractTest {

    @Test
    void exposesComputedRemainingTokensToAccountClients() throws Exception {
        var usage = new ModelAccountQuota.AccountUsage(
                "alice", true, 100_000, 1_000, 250, 500, LocalDate.of(2026, 8, 1), 3);

        var json = JsonMapper.builder().findAndAddModules().build().valueToTree(usage);

        assertThat(json.path("platformTokensRemaining").longValue()).isEqualTo(98_750);
    }
}
