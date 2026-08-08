package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoardGameRecommendationTaxonomyTest {

    @Test
    void treatsTheFormerAreaControlLabelAsTheCurrentBggMechanic() {
        assertThat(BoardGameRecommendationTaxonomy.equivalent("Area Control", "Area Majority / Influence"))
                .isTrue();
    }

    @Test
    void doesNotCollapseDifferentAreaMechanics() {
        assertThat(BoardGameRecommendationTaxonomy.equivalent("Area Control", "Area Movement"))
                .isFalse();
        assertThat(BoardGameRecommendationTaxonomy.equivalent("Area Control", "Enclosure"))
                .isFalse();
    }
}
