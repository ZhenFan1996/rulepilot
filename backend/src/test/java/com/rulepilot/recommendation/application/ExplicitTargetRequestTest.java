package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExplicitTargetRequestTest {

    @Test
    void extractsOneSettledPlayerSelectedTitleAcrossSupportedLocales() {
        assertThat(ExplicitTargetRequest.title(
                        "今晚已经决定玩星港（Harbor Nova），请直接找到这款并打开规则书。"))
                .contains("星港");
        assertThat(ExplicitTargetRequest.title(
                        "We've already decided to play River Market tonight, so open its rulebook."))
                .contains("River Market");
        assertThat(ExplicitTargetRequest.title(
                        "请帮我找到《雾中灯塔》，接下来想读规则书。"))
                .contains("雾中灯塔");
        assertThat(ExplicitTargetRequest.title(
                        "我们今晚第一次玩云海商路，规则书还没看。能帮我把这款找出来，然后带我们读规则吗？"))
                .contains("云海商路");
        assertThat(ExplicitTargetRequest.title(
                        "We're playing Lantern Harbor tonight, so find and open the rulebook."))
                .contains("Lantern Harbor");
    }

    @Test
    void leavesComparisonsMultipleTitlesAndOrdinaryDiscussionToTheAgent() {
        assertThat(ExplicitTargetRequest.title(
                        "我们已选定《Harbor Nova》和《Loom City》做比较。"))
                .isEmpty();
        assertThat(ExplicitTargetRequest.title(
                        "《Harbor Nova》的美术是谁画的？"))
                .isEmpty();
        assertThat(ExplicitTargetRequest.title(
                        "找几款像 Harbor Nova 的游戏，但不要直接替我选。"))
                .isEmpty();
    }
}
