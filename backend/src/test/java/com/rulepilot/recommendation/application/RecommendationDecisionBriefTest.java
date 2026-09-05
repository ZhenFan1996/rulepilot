package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationDecisionBriefTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RecommendationDecisionBrief decisions = new RecommendationDecisionBrief(json);

    @Test
    void rejectsAPlayerSummaryThatDoesNotDescribeTheChosenAction() {
        ToolCall call = call("research_game_fit", validBrief("search_bgg_catalog"));
        List<String> snapshots = new ArrayList<>();
        var publisher = decisions.streamingPublisher(
                "zh-CN", Set.of("search_bgg_catalog", "research_game_fit"), snapshots::add);

        publisher.accept(call);
        publisher.finish(call);

        assertThat(snapshots).isEmpty();
        assertThat(decisions.render(call, "zh-CN")).isEmpty();
    }

    @Test
    void rejectsExtraFieldsInsteadOfPublishingUnvalidatedModelText() {
        String arguments = validBrief("search_bgg_catalog")
                .replace("\"uncertainties\":[]", "\"uncertainties\":[],\"privateReasoning\":\"hidden\"");

        assertThat(decisions.render(call("search_bgg_catalog", arguments), "zh-CN")).isEmpty();
    }

    @Test
    void removesThePublicSummaryBeforeBusinessExecution() throws Exception {
        ToolCall compact = decisions.withoutBrief(call("search_bgg_catalog", validBrief("search_bgg_catalog")));

        assertThat(json.readTree(compact.argumentsJson()).has("decisionBrief")).isFalse();
        assertThat(json.readTree(compact.argumentsJson()).path("publicationCount").asInt()).isEqualTo(2);
    }

    @Test
    void publishesGrowingPlayerSafeSnapshotsAsTheProviderArgumentsArrive() {
        String arguments = validBrief("search_bgg_catalog");
        List<String> snapshots = new ArrayList<>();
        var publisher = decisions.streamingPublisher(
                "zh-CN", Set.of("search_bgg_catalog"), snapshots::add);
        StringBuilder accumulated = new StringBuilder();

        arguments.codePoints().forEach(codePoint -> {
            accumulated.appendCodePoint(codePoint);
            publisher.accept(call("search_bgg_catalog", accumulated.toString()));
        });
        publisher.finish(call("search_bgg_catalog", arguments));

        assertThat(snapshots.getFirst())
                .contains("我对这次请求的判断")
                .doesNotContain("下一步会核对什么");
        assertThat(snapshots.getLast())
                .contains("找适合四人的游戏", "先核对目录硬条件", "核对人数和时长");
        assertThat(snapshots)
                .extracting(String::length)
                .isSortedAccordingTo(Integer::compareTo);
    }

    @Test
    void doesNotPublishAStreamForAnActionOutsideTheOfferedSchema() {
        List<String> snapshots = new ArrayList<>();
        var publisher = decisions.streamingPublisher(
                "zh-CN", Set.of("research_game_fit"), snapshots::add);

        ToolCall call = call("search_bgg_catalog", validBrief("search_bgg_catalog"));
        publisher.accept(call);
        publisher.finish(call);

        assertThat(snapshots).isEmpty();
    }

    private ToolCall call(String name, String arguments) {
        return new ToolCall("decision", name, arguments);
    }

    private String validBrief(String chosenAction) {
        return "{\"decisionBrief\":{"
                + "\"chosenAction\":\"" + chosenAction + "\","
                + "\"understoodGoal\":\"找适合四人的游戏\","
                + "\"constraints\":[\"四人\"],"
                + "\"direction\":\"先核对目录硬条件\","
                + "\"decisionFactors\":[\"人数会淘汰不适配的候选\"],"
                + "\"nextStep\":\"核对人数和时长\","
                + "\"uncertainties\":[]},"
                + "\"evidence\":\"U1\",\"publicationCount\":2} ";
    }
}
