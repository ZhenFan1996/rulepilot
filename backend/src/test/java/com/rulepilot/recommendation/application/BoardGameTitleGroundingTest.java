package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoardGameTitleGroundingTest {

    @Test
    void extractsOneSettledCurrentTargetButNotComparisonOrDiscussion() {
        assertThat(BoardGameTitleGrounding.explicitTargetTitle(
                        "今晚已经决定玩星港（Harbor Nova），请直接找到这款并打开规则书。"))
                .contains("星港");
        assertThat(BoardGameTitleGrounding.explicitTargetTitle(
                        "We've already decided to play Harbor Nova tonight, so open its rulebook."))
                .contains("Harbor Nova");
        assertThat(BoardGameTitleGrounding.explicitTargetTitle(
                        "我们已选定《Harbor Nova》和《Loom City》做比较。"))
                .isEmpty();
        assertThat(BoardGameTitleGrounding.explicitTargetTitle(
                        "《Harbor Nova》的美术是谁画的？"))
                .isEmpty();
    }

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
