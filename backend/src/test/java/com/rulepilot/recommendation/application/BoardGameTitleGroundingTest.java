package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoardGameTitleGroundingTest {

    @Test
    void restoresOnlyAnImmediatePlayerAuthoredParenthesizedAlias() {
        assertThat(BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                        "今晚玩蓝瓷花园（Mosaic Field），不要换游戏。", "蓝瓷花园"))
                .contains("蓝瓷花园（Mosaic Field）");
        assertThat(BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                        "Tonight: River Market (Mercado del Río).", "River Market"))
                .contains("River Market (Mercado del Río)");
    }

    @Test
    void doesNotExpandAnEmbeddedWordOrAnIncompleteAlias() {
        assertThat(BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                        "Choose Cart(Game 20), not another title.", "Art"))
                .isEmpty();
        assertThat(BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                        "今晚玩蓝瓷花园（ ）。", "蓝瓷花园"))
                .isEmpty();
        assertThat(BoardGameTitleGrounding.withImmediateParenthesizedAlias(
                        "今晚玩蓝瓷花园（Mosaic Field。", "蓝瓷花园"))
                .isEmpty();
    }
}
