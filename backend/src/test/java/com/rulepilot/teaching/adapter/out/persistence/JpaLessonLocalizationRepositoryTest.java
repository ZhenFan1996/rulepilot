package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.LessonLocalization;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaLessonLocalizationRepositoryTest {

    @Test
    void storesAnEmptyJsonArrayForAnUnfinishedProjection() {
        ObjectMapper json = new ObjectMapper();
        LessonLocalization localization = LessonLocalization.pending(
                UUID.randomUUID(), PlayerLocale.EN, Instant.parse("2026-07-23T00:00:00Z"));

        LessonLocalizationEntity entity = new LessonLocalizationEntity(localization, json);

        assertThat(entity.translatedSections).isEqualTo("[]");
        assertThat(entity.toDomain(json).sections()).isEmpty();
    }
}
