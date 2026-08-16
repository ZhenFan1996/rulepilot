package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExplicitRecommendationQuantityTest {

    @Test
    void readsExplicitChineseAndEnglishResultCountsWithoutConfusingOtherNumbers() {
        assertThat(ExplicitRecommendationQuantity.from("今晚随便挑两款方向不同的桌游", 8))
                .hasValue(2);
        assertThat(ExplicitRecommendationQuantity.from("给我 ３ 款，并说清取舍", 8))
                .hasValue(3);
        assertThat(ExplicitRecommendationQuantity.from("别只给三款，我现在想先看五个不同方向。", 8))
                .hasValue(5);
        assertThat(ExplicitRecommendationQuantity.from("Please give me two board games with tradeoffs.", 8))
                .hasValue(2);
        assertThat(ExplicitRecommendationQuantity.from("Recommend 4 options for tonight.", 8))
                .hasValue(4);

        assertThat(ExplicitRecommendationQuantity.from(
                        "我们 3 到 4 个人，想找 30 到 60 分钟、复杂度不超过 3.0 的桌游。", 8))
                .isEmpty();
        assertThat(ExplicitRecommendationQuantity.from(
                        "先给三款，再从中换成两款。", 8))
                .as("conflicting quantities need semantic resolution instead of a deterministic guess")
                .isEmpty();
    }
}
