package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingSectionType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeachingRetrievalPlannerTest {

    @Test
    void providesTwoDistinctBoundedIntentsForEveryTeachingSection() {
        for (TeachingSectionType type : TeachingSectionType.values()) {
            var intents = TeachingRetrievalPlanner.forSection(type);
            assertThat(intents).as(type.name()).hasSize(2);
            assertThat(intents.get(0).query()).isNotEqualTo(intents.get(1).query());
            assertThat(intents).allSatisfy(intent -> {
                assertThat(intent.query()).isNotBlank().hasSizeLessThanOrEqualTo(500);
                assertThat(intent.sourceTypes()).isNotEmpty().hasSizeLessThanOrEqualTo(6);
            });
        }

        assertThat(TeachingRetrievalPlanner.forSection(TeachingSectionType.SETUP).get(1).sourceTypes())
                .containsExactly("SETUP");
        assertThat(TeachingRetrievalPlanner.forSection(TeachingSectionType.SCORING).get(1).sourceTypes())
                .containsExactly("SCORING");
        assertThat(TeachingRetrievalPlanner.forSection(TeachingSectionType.RECAP).get(1).sourceTypes())
                .contains("END_CONDITIONS", "SCORING", "TIE_BREAKERS");
    }

    @Test
    void refinesSupplementaryIntentWithTwoSanitizedDistinctAnchorHeadings() {
        var supplementary = TeachingRetrievalPlanner.forSection(TeachingSectionType.SETUP).get(1);

        var refined = TeachingRetrievalPlanner.refineWithAnchorHeadings(
                supplementary,
                List.of(
                        "  River Board\nPreparation  ",
                        "River Board Preparation",
                        "Starting Inventories",
                        "Ignored Third Heading"));

        assertThat(refined.query())
                .contains("River Board Preparation", "Starting Inventories")
                .doesNotContain("Ignored Third Heading", "\n");
        assertThat(refined.sourceTypes()).isEqualTo(supplementary.sourceTypes());
    }

    @Test
    void keepsOriginalIntentWhenAnAnchorWouldExceedTheQueryLimit() {
        var intent = new TeachingRetrievalPlanner.RetrievalIntent("x".repeat(495), Set.of("SETUP"));

        var refined = TeachingRetrievalPlanner.refineWithAnchorHeadings(intent, List.of("Long heading"));

        assertThat(refined).isEqualTo(intent);
        assertThat(refined.query()).hasSizeLessThanOrEqualTo(500);
        assertThat(TeachingRetrievalPlanner.refineWithAnchorHeadings(intent, List.of())).isEqualTo(intent);
    }
}
