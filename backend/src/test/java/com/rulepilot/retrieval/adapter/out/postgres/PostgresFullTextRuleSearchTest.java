package com.rulepilot.retrieval.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresFullTextRuleSearchTest {

    @Test
    void keepsDistinctiveTermsFromTheEndOfALongFallbackQuery() {
        String fallback = PostgresFullTextRuleSearch.fallbackQuery(
                "end game end of round cleanup end-game check fame scoring winner tie gold "
                        + "pledged cargo final resolution");

        assertThat(fallback).contains("pledged", "cargo", "gold", "tie", "fame");
    }
}
