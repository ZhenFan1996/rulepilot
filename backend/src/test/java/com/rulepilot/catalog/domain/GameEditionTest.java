package com.rulepilot.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameEditionTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void confirmsACanonicalSourceLanguageOnlyWhileTheEditionIsUnknown() {
        var unknown = new GameEdition(
                UUID.randomUUID(), UUID.randomUUID(), "Base edition", "und", 2024, NOW);

        var confirmed = unknown.confirmLanguageIfUnknown("zh_cn");

        assertThat(confirmed.language()).isEqualTo("zh-CN");
        assertThat(confirmed.id()).isEqualTo(unknown.id());
        assertThat(confirmed.gameId()).isEqualTo(unknown.gameId());
        assertThat(confirmed.createdAt()).isEqualTo(NOW);
    }

    @Test
    void neverOverwritesAnExistingKnownEditionLanguage() {
        var chinese = new GameEdition(
                UUID.randomUUID(), UUID.randomUUID(), "Chinese edition", "zh-CN", 2024, NOW);

        assertThat(chinese.confirmLanguageIfUnknown("en")).isSameAs(chinese);
    }
}
