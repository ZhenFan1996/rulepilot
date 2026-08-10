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

    @Test
    void bindsTheEndingTopicToAnActualEndingPageAndSkipsAssemblyOrPromotionInserts() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "TEST GAME", "cover; no game mechanism"),
                        visualPage(2, "SET UP", "setup steps and starting resources"),
                        visualPage(3, "HOW TO PLAY", "turn phases and actions"),
                        visualPage(4, "END OF GAME", "when a runner reaches the finish space, determine the winner"),
                        visualPage(5, "DICE BOX ASSEMBLY", "storage or assembly instructions, not gameplay"),
                        visualPage(6, "OTHER GAME", "advertisement for another game; non-gameplay material"),
                        visualPage(7, "COMPONENTS", "component list; see page 19 for storage or assembly instructions")),
                List.of()));

        assertThat(topicPages(outline, "结束、计分与胜者")).contains(4).doesNotContain(5, 6);
        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).contains(7).doesNotContain(5, 6);
    }

    @Test
    void excludesAnEnglishCoverWithoutDroppingAComponentsPageThatMentionsStorage() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "RULEBOOK COVER", "cover artwork. No gameplay rules, components, or operational instructions are present."),
                        visualPage(2, "COMPONENTS", "player boards, fan track, phase tokens, dice boxes, and a storage box; see page 19 for assembly"),
                        visualPage(3, "SET UP", "starting resources"),
                        visualPage(4, "HOW TO PLAY", "turn phases"),
                        visualPage(5, "END OF GAME", "winner and scoring")),
                List.of()));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).contains(2).doesNotContain(1);
    }

    @Test
    void excludesAnIdentityOnlyFirstPageEvenWhenTheVisualModelDoesNotCallItACover() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "HARBOR LIGHTS; NORTH STAR GAMES", "页面显示游戏标题、出版商标志与设计者姓名。"),
                        visualPage(2, "COMPONENTS", "component list"),
                        visualPage(3, "SET UP", "starting resources"),
                        visualPage(4, "HOW TO PLAY", "players take turns choosing actions"),
                        visualPage(5, "END OF GAME", "when the final tile is taken, the highest score wins")),
                List.of()));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).doesNotContain(1);
    }

    @Test
    void retainsACompactRulesSheetOnTheFirstPage() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(visualPage(
                        1,
                        "POCKET RELAY; SET UP; YOUR TURN; SCORING",
                        "每位玩家拿取三张牌；回合中选择一项行动；牌堆耗尽时游戏结束并比较分数。")),
                List.of()));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).contains(1);
    }

    @Test
    void excludesAnIdentityOnlyBackPageEvenWhenDecorativeArtIsMistakenForComponentTerms() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "SET UP", "Each player takes three cards."),
                        visualPage(2, "HOW TO PLAY", "On your turn, choose one action."),
                        visualPage(3, "END OF GAME", "When the deck is empty, the highest score wins."),
                        visualPage(
                                4,
                                "POINT CARD; RESOURCE TOKEN; AWARD FINALIST",
                                "装饰性背景图案位于整页；底部显示奖项、品牌标志与出版商徽标。")),
                List.of()));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).doesNotContain(4);
    }

    @Test
    void retainsALaterRulesPageThatAlsoHasAPublisherFooter() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "SET UP", "Each player takes three cards."),
                        visualPage(
                                2,
                                "YOUR TURN; PUBLISHER",
                                "On your turn, choose one action. A publisher logo appears in the footer."),
                        visualPage(3, "END OF GAME", "When the deck is empty, the highest score wins.")),
                List.of()));

        assertThat(outline.topics()).flatExtracting(topic -> topic.sourcePageNumbers()).contains(2);
    }

    @Test
    void doesNotMistakeAFinishSpaceTargetForTheGameObjective() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "设置", "游戏设置与起始资源"),
                        visualPage(2, "游戏流程", "回合包括掷骰和移动阶段"),
                        visualPage(3, "终局", "到达终点空间时触发游戏结束；目标空间与捷径的颜色须相同；获胜者由最远距离决定")),
                List.of()));

        assertThat(topicPages(outline, "结束、计分与胜者")).contains(3);
        assertThat(topicPages(outline, "目标、组件与关键信息")).doesNotContain(3);
    }

    @Test
    void requiresAnEndingTriggerAndWinnerResolutionBeforeRoutingAPageToTheEndingTopic() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "游戏流程", "每轮重复进行，直到游戏结束。"),
                        visualPage(2, "终局", "当任何跑者到达终点空间时游戏结束；完成当前回合后，最远的玩家获胜，平局则继续比赛。")),
                List.of()));

        assertThat(topicPages(outline, "结束、计分与胜者")).contains(2).doesNotContain(1);
        assertThat(outline.topics())
                .flatExtracting(topic -> topic.sourcePageNumbers())
                .contains(1);
    }

    @Test
    void recognizesGameEndAsAnEndgameHeadingWhenTheVisibleFactsResolveTheWinner() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "SET UP", "setup steps and starting resources"),
                        visualPage(2, "GAMEPLAY", "turn phases and actions"),
                        visualPage(3, "GAME END; SCORING", "The game ends when the final card is taken. The highest score wins.")),
                List.of()));

        assertThat(topicPages(outline, "结束、计分与胜者")).contains(3);
    }

    @Test
    void recognizesATriggeredGameEndBeforeTheWinnerRuleIsStated() {
        var outline = new FakeTeachingOutlineModel().organize(new OutlineRequest(

                List.of(
                        visualPage(1, "SET UP", "setup steps and starting resources"),
                        visualPage(2, "GAMEPLAY", "turn phases and actions"),
                        visualPage(3, "END OF GAME", "任一玩家达到至少40分即触发游戏结束；完成本回合后再比较胜利点。")),
                List.of()));

        assertThat(topicPages(outline, "结束、计分与胜者")).contains(3);
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
