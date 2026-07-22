package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeTeachingOutlineModelTest {

    @Test
    void retainsEverySubstantiveVisualCatalogPageWhenTheLivePlannerFallsBack() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(
                4,
                4,
                30,
                List.of(
                        visualPage(1, "TEST GAME", "cover; no game mechanism"),
                        visualPage(2, "COMPONENTS", "component list"),
                        visualPage(3, "SET UP", "starting resources"),
                        visualPage(4, "GAME OVERVIEW", "round sequence"),
                        visualPage(5, "ACTIONS", "deploy or move"),
                        visualPage(6, "FAQ", "attack restriction"),
                        visualPage(7, "EXAMPLE", "reference state"),
                        visualPage(8, "HOW TO WIN", "victory condition")),
                List.of()));

        assertThat(outline.gameTitle()).isEqualTo("TEST GAME");
        assertThat(outline.topics())
                .flatExtracting(topic -> topic.sourcePageNumbers())
                .contains(2, 3, 4, 5, 6, 7, 8)
                .doesNotContain(1);
        assertThat(outline.topics())
                .flatExtracting(topic -> topic.coverageTags())
                .contains("setup", "core_loop", "end", "scoring");
    }

    @Test
    void keepsCoversOutAndRoutesFaqAndHistoricalPagesToReaderAppropriateFallbackSections() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(
                5,
                5,
                30,
                List.of(
                        visualPage(1, "TEST GAME", "封面；页面无游戏规则文字，仅作为视觉封面存在"),
                        visualPage(2, "GAME OVERVIEW", "draw coins each round"),
                        visualPage(3, "FAMOUS BATTLE", "historical scenario and battle setup"),
                        visualPage(4, "FAQ", "frequently asked restrictions"),
                        visualPage(5, "ACTIONS", "maneuver, move and control")),
                List.of()));

        assertThat(topicPages(outline, "示例、变体与速查")).contains(3);
        assertThat(topicPages(outline, "限制、FAQ 与常见例外")).contains(4);
        assertThat(topicPages(outline, "主要行动的选择与执行")).contains(5);
        assertThat(outline.topics())
                .flatExtracting(topic -> topic.sourcePageNumbers())
                .doesNotContain(1);
    }

    private List<Integer> topicPages(com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft outline, String title) {
        return outline.topics().stream()
                .filter(topic -> topic.title().equals(title))
                .findFirst()
                .orElseThrow()
                .sourcePageNumbers();
    }

    private PageInput visualPage(int number, String terms, String facts) {
        return new PageInput(
                number,
                "[Visual page catalog; verify against page image]\nPrinted terms: "
                        + terms
                        + "\nVisible facts: "
                        + facts
                        + "\nKeywords: test");
    }
}
