package com.rulepilot.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardGameRecommendationCatalogTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void restoredSpecificationsDistinguishUnknownValuesFromPositiveMeasurements(int value) throws Exception {
        Details details = new ObjectMapper().readValue("""
                {"minPlayers":%1$d,"maxPlayers":%1$d,"playingTimeMinutes":%1$d,
                 "minimumPlayTimeMinutes":%1$d,"maximumPlayTimeMinutes":%1$d,
                 "minimumAge":%1$d,"suggestedMinimumAge":%1$d,"languageDependenceLevel":%1$d,
                 "averageWeight":%1$d,"weightVotes":0,"categories":[],"mechanics":[],
                 "families":[],"designers":[],"publishers":[]}
                """.formatted(value), Details.class);

        Integer expected = value == 0 ? null : value;
        assertThat(details.minPlayers()).isEqualTo(expected);
        assertThat(details.maxPlayers()).isEqualTo(expected);
        assertThat(details.playingTimeMinutes()).isEqualTo(expected);
        assertThat(details.minimumPlayTimeMinutes()).isEqualTo(expected);
        assertThat(details.maximumPlayTimeMinutes()).isEqualTo(expected);
        assertThat(details.minimumAge()).isEqualTo(expected);
        assertThat(details.suggestedMinimumAge()).isEqualTo(expected);
        assertThat(details.languageDependenceLevel()).isEqualTo(expected);
        assertThat(details.averageWeight()).isEqualTo(value == 0 ? null : BigDecimal.valueOf(value));
        assertThat(details.weightVotes()).as("zero remains a valid count of votes").isZero();
    }
}
