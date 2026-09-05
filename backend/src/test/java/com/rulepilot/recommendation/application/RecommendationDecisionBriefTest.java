package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationDecisionBriefTest {

    private static final String PUBLIC_MESSAGE = "我会先查找适合四人的游戏，再核对时长。\n\n如果资料有不确定的地方，我会直接说明。";

    private final ObjectMapper json = new ObjectMapper();
    private final RecommendationDecisionBrief decisions = new RecommendationDecisionBrief(json);

    @Test
    void rejectsAPlayerSummaryThatDoesNotDescribeTheChosenAction() {
        ToolCall call = call("research_game_fit", validBrief("search_bgg_catalog"));
        List<String> snapshots = new ArrayList<>();
        var publisher = decisions.streamingPublisher(
                Set.of("search_bgg_catalog", "research_game_fit"), snapshots::add);

        publisher.accept(call);
        publisher.finish(call);

        assertThat(snapshots).isEmpty();
        assertThat(decisions.render(call)).isEmpty();
    }

    @Test
    void rejectsExtraFieldsInsteadOfPublishingUnvalidatedModelText() {
        String arguments = validBrief("search_bgg_catalog")
                .replace("\"message\":", "\"privateReasoning\":\"hidden\",\"message\":");

        assertThat(decisions.render(call("search_bgg_catalog", arguments))).isEmpty();
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
                Set.of("search_bgg_catalog"), snapshots::add);
        StringBuilder accumulated = new StringBuilder();

        arguments.codePoints().forEach(codePoint -> {
            accumulated.appendCodePoint(codePoint);
            publisher.accept(call("search_bgg_catalog", accumulated.toString()));
        });
        publisher.finish(call("search_bgg_catalog", arguments));

        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.getFirst()).isNotEqualTo(PUBLIC_MESSAGE);
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(PUBLIC_MESSAGE).startsWith(snapshot));
        assertThat(snapshots.getLast()).isEqualTo(PUBLIC_MESSAGE);
        assertThat(decisions.render(call("search_bgg_catalog", arguments)))
                .contains(PUBLIC_MESSAGE);
    }

    @Test
    void doesNotPublishAStreamForAnActionOutsideTheOfferedSchema() {
        List<String> snapshots = new ArrayList<>();
        var publisher = decisions.streamingPublisher(
                Set.of("research_game_fit"), snapshots::add);

        ToolCall call = call("search_bgg_catalog", validBrief("search_bgg_catalog"));
        publisher.accept(call);
        publisher.finish(call);

        assertThat(snapshots).isEmpty();
    }

    private ToolCall call(String name, String arguments) {
        return new ToolCall("decision", name, arguments);
    }

    private String validBrief(String chosenAction) {
        var root = json.createObjectNode();
        root.set("decisionBrief", json.createObjectNode()
                .put("chosenAction", chosenAction)
                .put("message", PUBLIC_MESSAGE));
        return root.put("evidence", "U1").put("publicationCount", 2).toString();
    }
}
