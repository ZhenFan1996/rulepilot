package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class TeachingPlanServiceTest {

    @Test
    void cancelsASlowVisualCatalogBatchInsteadOfWaitingForTheProviderTimeout() throws InterruptedException {
        FutureTask<VisualRulebookPageCatalogModel.CatalogDraft> slowCatalog = new FutureTask<>(() -> {
            Thread.sleep(5_000);
            return new VisualRulebookPageCatalogModel.CatalogDraft(List.of());
        });
        Thread worker = new Thread(slowCatalog, "slow-visual-catalog-test");
        worker.start();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TeachingPlanService.awaitCatalog(slowCatalog, Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        worker.join(250);
        assertThat(slowCatalog.isCancelled()).isTrue();
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void selectsOnlyBoundedPlannerRequestedPagesForVisualInterpretation() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        topic("setup", false, List.of(1, 2)),
                        topic("cards", true, List.of(5, 6, 7, 8)),
                        topic("icons", true, List.of(7, 9, 10, 11)),
                        topic("end", false, List.of(12))));

        List<PageView> pages = List.of(
                page(1, "Introduction"),
                page(5, "To use this ability, pay 2  ."),
                page(6, "Card diagram"),
                page(7, "Setup: Give each player two energy markers and one score token. Keep markers behind the screen."),
                page(8, "Main phase"),
                page(9, "When scoring, gain 2  , and if you have 10  ."),
                page(10, "End of game"),
                page(11, "Appendix"),
                page(12, "Credits"));

        assertThat(TeachingPlanService.selectedVisualPageNumbers(outline, pages))
                .containsExactly(7, 5, 9, 6);
    }

    @Test
    void includesTheIconLegendEvenWhenThePlannerOnlyReferencesTheOperationalPage() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("wager", false, List.of(14))));
        List<PageView> pages = List.of(
                page(7, "Setup: Give each player two energy markers and one score token. Keep markers behind the screen."),
                page(14, "You may use this wager only if you have at least 2  . Place 2  on it."));

        assertThat(TeachingPlanService.selectedVisualPageNumbers(outline, pages))
                .containsExactly(7, 14);
    }

    @Test
    void bindsTheIconLegendAsCitableEvidenceForAChapterWithMissingInlineIcons() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("wager", false, List.of(14))));
        List<PageView> pages = List.of(
                page(3, "Components: 36 energy markers, 76 score tokens, 12 map tiles, and 40 cards."),
                page(7, "Setting up: Give each player energy markers placed behind the screen and one score token placed in front."),
                page(14, "Use this wager only if you have at least 2  . Place 2  on it."));

        OutlineDraft bound = TeachingPlanService.bindIconLegendEvidence(outline, pages);

        assertThat(bound.topics().getFirst().sourcePageNumbers()).containsExactly(14, 7);
    }

    @Test
    void prioritizesTheIconLegendWithoutExceedingTheFivePageTopicLimit() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("wager", false, List.of(14, 15, 16, 17))));
        List<PageView> pages = List.of(
                page(3, "Components: 36 energy markers, 76 score tokens, 12 map tiles, and 40 cards."),
                page(7, "Setting up: Give each player energy markers placed behind the screen and one score token placed in front."),
                page(14, "Use this wager only if you have at least 2  . Place 2  on it."));

        OutlineDraft bound = TeachingPlanService.bindIconLegendEvidence(outline, pages);

        assertThat(bound.topics().getFirst().sourcePageNumbers()).containsExactly(14, 15, 16, 17, 7);
    }

    @Test
    void detectsAChineseComponentReferenceWithoutKnowingAnyGameSpecificNames() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("market", true, List.of(8))));
        List<PageView> pages = List.of(
                page(2, "组件：12枚月亮指示物、24枚声望令牌、6块区域板块和48张卡牌。"),
                page(4, "设置：每位玩家拿取2枚月亮指示物和1枚声望令牌，放在自己面前。"),
                page(8, "支付2  后，从市场拿取一张卡牌。"));

        assertThat(TeachingPlanService.selectedVisualPageNumbers(outline, pages))
                .containsExactly(4, 8);
        assertThat(TeachingPlanService.bindIconLegendEvidence(outline, pages)
                        .topics().getFirst().sourcePageNumbers())
                .containsExactly(8, 4);
    }

    @Test
    void requestsARevisionWhenAFlowChapterExplainsDetailsOwnedByLaterChapters() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic(
                                "turn-flow",
                                "玩家回合流程",
                                "说明阶段顺序、印记记忆的支付费用、获得EP和手牌上限。"),
                        detailedTopic(
                                "imprint-costs",
                                "印记记忆的费用与奖励",
                                "说明支付条件、折扣和奖励。"),
                        detailedTopic(
                                "cleanup",
                                "回合结束清理",
                                "说明手牌上限和弃牌。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("玩家回合流程", "印记记忆的费用与奖励", "回合结束清理", "Keep the bridge"));
    }

    @Test
    void doesNotReviseAFlowChapterWhenNoLaterChapterOwnsItsDetails() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic("turn-flow", "玩家回合流程", "说明阶段顺序、额外行动和手牌限制。"),
                        detailedTopic("end", "游戏结束", "说明结束条件和胜者。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline)).isEmpty();
    }

    @Test
    void skipsASecondOwnershipRevisionWhenCoverageKeptTheSameOutline() {
        OutlineDraft outline = new OutlineDraft("Game", "Premise", List.of(topic("setup", false, List.of(2))));

        assertThat(TeachingOutlineRevisionPolicy.requiresChapterOwnershipRerun(outline, outline)).isFalse();
        assertThat(TeachingOutlineRevisionPolicy.requiresChapterOwnershipRerun(
                        outline,
                        new OutlineDraft("Game", "Premise", List.of(topic("setup", false, List.of(2, 3))))))
                .isTrue();
    }

    @Test
    void requestsARevisionWhenAnEarlierRewardChapterExplainsALaterEndTrigger() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic(
                                "earn-points",
                                "获得启蒙点",
                                "说明获得EP后如何移动标记，并在满足条件时触发游戏结束。"),
                        detailedTopic(
                                "game-end",
                                "游戏结束与最终计分",
                                "说明内心指南针的结束触发、最后一轮与计分。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("获得启蒙点", "游戏结束与最终计分", "game-end trigger"));
    }

    @Test
    void requestsARevisionWhenAnEndTriggerIsRepeatedAfterItsOwnerChapter() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic(
                                "game-end",
                                "游戏结束与最终计分",
                                "说明内心指南针的结束触发、最后一轮与计分。"),
                        detailedTopic(
                                "value-tokens",
                                "放置价值标记",
                                "说明放置价值标记，并在放完两个后再次完成一行时触发游戏结束。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("放置价值标记", "游戏结束与最终计分", "game-end trigger"));
    }

    @Test
    void requestsARevisionWhenTurnCleanupIsPlacedAfterTheFinale() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic("turn-flow", "玩家回合流程", "说明移动与行动选择。"),
                        detailedTopic("game-end", "游戏结束与最终计分", "说明结束触发、最终计分和平局。"),
                        detailedTopic("cleanup", "回合结束清理", "说明手牌上限、弃牌与补牌。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("回合结束清理", "游戏结束与最终计分", "before game end and final scoring"));
    }

    @Test
    void requestsARevisionWhenScoringCriteriaFollowTheFinale() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        detailedTopic("turn-flow", "玩家回合流程", "说明移动与行动选择。"),
                        detailedTopic("game-end", "游戏结束与最终计分", "说明结束触发、最终计分和平局。"),
                        detailedTopic("quality-scoring", "品质瓷砖计分", "说明每个品质瓷砖的得分条件。")));

        assertThat(TeachingOutlineRevisionPolicy.chapterOwnershipRevisionFeedback(outline))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("品质瓷砖计分", "游戏结束与最终计分", "scoring detail before the end/final-scoring conclusion"));
    }

    @Test
    void requestsARevisionWhenASubstantiveRulebookPageHasNoTeachingOwner() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(new TopicDraft(
                        "setup",
                        "游戏设置",
                        "说明标准设置。",
                        true,
                        false,
                        List.of("SETUP"),
                        List.of("setup"),
                        List.of(2))));

        assertThat(TeachingOutlineRevisionPolicy.sourcePageCoverageRevisionFeedback(
                        outline,
                        List.of(
                                new PageInput(1, "INNER COMPASS"),
                                new PageInput(2, "SETUP Give each player a board."),
                                new PageInput(3, "ADVANCED RULES: special action icons."))))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("Page 3", "ADVANCED RULES")
                        .doesNotContain("Page 1"));
    }

    @Test
    void samplesOnlyUnownedSparsePagesThatTheTextCoverageCannotAlreadyExplain() {
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(topic("setup", false, List.of(2))));
        List<PageView> pages = List.of(
                page(1, "GAME TITLE"),
                page(2, "SETUP Give each player a board."),
                page(3, "◆ ◆"),
                page(4, "ADVANCED RULES: special action icons."),
                page(5, "◇"),
                page(6, "◉ ◉"),
                page(7, "△"));

        assertThat(TeachingPlanService.unownedSparseVisualCoveragePageNumbers(outline, pages, 4))
                .containsExactly(3, 5, 6, 7)
                .doesNotContain(1, 2, 4);
    }

    @Test
    void letsAConcreteVisualLedgerRequestCoverageWithoutDiscardingTheExtractedText() {
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(topic("setup", false, List.of(2))));
        List<PageInput> merged = TeachingPlanService.mergeVisualFactsIntoPageInputs(
                List.of(
                        new PageInput(2, "SETUP Give each player a board."),
                        new PageInput(3, "◆ ◆")),
                List.of(new PageFact(
                        3,
                        "ACTION ICONS",
                        "页面显示两个带箭头的行动图标，并在旁边印有对应标签。",
                        List.of("ACTION", "ICONS"))));

        assertThat(merged.getLast().text()).contains("◆ ◆", "[Visual page catalog;", "ACTION ICONS");
        assertThat(TeachingOutlineRevisionPolicy.sourcePageCoverageRevisionFeedback(outline, merged))
                .hasValueSatisfying(feedback -> assertThat(feedback)
                        .contains("Page 3", "ACTION ICONS", "Visual page catalog")
                        .doesNotContain("◆ ◆"));
    }

    @Test
    void doesNotCreateCoverageWorkFromAnUncatalogedSparseVisualPage() {
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(topic("setup", false, List.of(2))));
        List<PageInput> pages = TeachingPlanService.visualPageInputs(
                List.of(page(2, "SETUP Give each player a board."), page(3, "")),
                List.of());

        assertThat(TeachingOutlineRevisionPolicy.sourcePageCoverageRevisionFeedback(outline, pages)).isEmpty();
    }

    @Test
    void retainsEveryVisualOnlySourcePageWhenOneVisualCatalogBatchTimesOut() {
        List<PageInput> inputs = TeachingPlanService.visualPageInputs(
                List.of(page(1, ""), page(2, ""), page(3, "")),
                List.of(
                        new PageFact(1, "WAR CHEST", "盒面显示两方军队。", List.of("WAR CHEST")),
                        new PageFact(3, "UNIT", "单位圆片有正反两面。", List.of("UNIT"))));

        assertThat(inputs).extracting(PageInput::pageNumber).containsExactly(1, 2, 3);
        assertThat(inputs.getFirst().text()).contains("WAR CHEST", "两方军队");
        assertThat(inputs.get(1).text())
                .startsWith("[Visual page catalog;")
                .contains("No factual visual claim", "source binding", "page 2");
        assertThat(inputs.getLast().text()).contains("UNIT", "正反两面");
    }

    @Test
    void reusesImmutableVersionFactsAndRequestsOnlyUncatalogedPages() {
        List<PageFact> cached = List.of(
                new PageFact(1, "COVER", "视觉封面。", List.of("COVER")),
                new PageFact(3, "END", "完整终局规则。", List.of("END")));
        List<PageFact> fresh = List.of(new PageFact(2, "SET UP", "设置规则。", List.of("SET UP")));

        assertThat(TeachingPlanService.missingVisualCatalogPages(java.util.Set.of(1, 2, 3), cached))
                .containsExactly(2);
        assertThat(TeachingPlanService.mergeVisualPageFacts(cached, fresh))
                .extracting(PageFact::pageNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void backfills_compact_visual_anchors_without_rewriting_the_cached_page_ledger() {
        PageFact cached = new PageFact(3, "END", "原有的终局事实。", List.of("END"));
        PageFact refreshed = new PageFact(
                3,
                "Changed title",
                "不应替换原有事实。",
                List.of("changed"),
                List.of(new VisualAnchor("score group", "Final scoring", "终局计分卡牌组。", 120, 420, 300, 180)));

        var merged = TeachingPlanService.mergeVisualPageAnchorBackfill(List.of(cached), List.of(refreshed));

        assertThat(TeachingPlanService.anchorlessVisualCatalogPages(List.of(cached))).containsExactly(3);
        assertThat(merged).singleElement().satisfies(fact -> {
            assertThat(fact.factualSummary()).isEqualTo("原有的终局事实。");
            assertThat(fact.keywords()).containsExactly("END");
            assertThat(fact.visualAnchors()).containsExactlyElementsOf(refreshed.visualAnchors());
        });
    }

    @Test
    void doesNotRequireAnUnrelatedPromotionOrStorageInsertInVisualRulebookCoverage() {
        List<PageInput> inputs = List.of(
                new PageInput(1, "[Visual page catalog; verify against page image]\nVisible facts: 核心回合流程。"),
                new PageInput(2, "[Visual page catalog; verify against page image]\nVisible facts: 这是与本规则书无关的宣传页，属于非游戏规则材料。"),
                new PageInput(3, "[Visual page catalog; verify against page image]\nVisible facts: 仅为收纳或组装说明，非游戏玩法规则。"),
                new PageInput(4, "[Visual page catalog; verify against page image]\nVisible facts: 本页为另一款游戏的宣传广告页，不包含本游戏玩法规则。"));
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(topic("round", true, List.of(1))));

        TeachingPlanService.validateVisualRulebookCoverage(outline, inputs);
    }

    @Test
    void requiresCoreVisualTopicsToUsePagesThatActuallyDescribeTheRequiredOutcome() {
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("SET UP", "Setup: distribute starting resources.")),
                new PageInput(2, visualCatalogPage("HOW TO PLAY", "Turn phases and actions.")),
                new PageInput(3, visualCatalogPage("DICE BOX ASSEMBLY", "Storage or assembly instructions, not gameplay.")),
                new PageInput(4, visualCatalogPage("END OF GAME", "When a runner reaches the finish space, determine the winner.")));
        OutlineDraft wrongEnding = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(3))));
        OutlineDraft supportedEnding = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(4))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TeachingPlanService.validateVisualCoreTopicBindings(wrongEnding, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end");
        TeachingPlanService.validateVisualCoreTopicBindings(supportedEnding, pages);
    }

    @Test
    void bindsAVisualCoreTopicToExistingDirectPageFactsBeforeRejectingThePlan() {
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("SET UP", "Setup: distribute starting resources.")),
                new PageInput(2, visualCatalogPage("HOW TO PLAY", "Turn phases and actions.")),
                new PageInput(3, visualCatalogPage("END", "Players repeat the flow until the game ends.")),
                new PageInput(4, visualCatalogPage("END OF GAME", "When a runner reaches the finish space, determine the winner.")));
        OutlineDraft misplacedEnding = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(3))));

        OutlineDraft repaired = TeachingPlanService.bindVisualCoreTopicEvidence(misplacedEnding, pages);

        assertThat(repaired.topics().get(2).sourcePageNumbers()).containsExactly(4, 3);
        TeachingPlanService.validateVisualCoreTopicBindings(repaired, pages);
    }

    @Test
    void prioritizesDirectVisualCoreEvidenceWithoutExceedingTheFivePageTopicLimit() {
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("SET UP", "Setup: distribute starting resources.")),
                new PageInput(2, visualCatalogPage("HOW TO PLAY", "Turn phases and actions.")),
                new PageInput(4, visualCatalogPage("END OF GAME", "When a runner reaches the finish space, determine the winner.")));
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(5, 6, 7, 8))));

        OutlineDraft repaired = TeachingPlanService.bindVisualCoreTopicEvidence(outline, pages);

        assertThat(repaired.topics().get(2).sourcePageNumbers()).containsExactly(4, 5, 6, 7, 8);
        TeachingPlanService.validateVisualCoreTopicBindings(repaired, pages);
    }

    @Test
    void retainsAllSourcePagesWhenTheCoreTopicAlreadyHasDirectVisualEvidence() {
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("SET UP", "Setup: distribute starting resources.")),
                new PageInput(2, visualCatalogPage("HOW TO PLAY", "Turn phases and actions.")),
                new PageInput(3, visualCatalogPage("PLAYER AID", "A reference table.")),
                new PageInput(4, visualCatalogPage("EXAMPLE", "An annotated round example.")));
        OutlineDraft outline = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2, 3, 4, 5))));

        OutlineDraft repaired = TeachingPlanService.bindVisualCoreTopicEvidence(outline, pages);

        assertThat(repaired.topics().get(1).sourcePageNumbers()).containsExactly(2, 3, 4, 5);
    }

    @Test
    void restoresASourcePageDisplacedByNewDirectCoreEvidenceInTheExistingTopic() {
        List<PageInput> pages = List.of(
                new PageInput(4, visualCatalogPage("END OF GAME", "When a runner reaches the finish space, determine the winner.")),
                new PageInput(5, visualCatalogPage("REFERENCE", "Additional reference information.")),
                new PageInput(6, visualCatalogPage("EXAMPLE", "An example state.")),
                new PageInput(7, visualCatalogPage("GLOSSARY", "Defined terms.")),
                new PageInput(8, visualCatalogPage("APPENDIX", "A final rules clarification.")));
        OutlineDraft source = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(4)),
                        topicWithTags("play", List.of("core_loop"), List.of(5)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(5, 6, 7, 8))));

        OutlineDraft bound = TeachingPlanService.bindVisualCoreTopicEvidence(source, pages);
        OutlineDraft restored = TeachingPlanService.augmentVisualCoverage(bound, source);

        assertThat(restored.topics().get(2)).satisfies(topic -> {
            assertThat(topic.sourcePageNumbers()).containsExactly(4, 5, 6, 7, 8);
            assertThat(topic.coverageTags()).contains("end", "scoring");
        });
        TeachingPlanService.validateVisualRulebookCoverage(restored, pages);
    }

    @Test
    void doesNotTreatAFlowPageThatMentionsGameEndAsCompleteEndingEvidence() {
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("SET UP", "Setup: distribute starting resources.")),
                new PageInput(2, visualCatalogPage("HOW TO PLAY", "Turn phases and actions.")),
                new PageInput(3, visualCatalogPage("FLOW", "Each round repeats until the game ends.")),
                new PageInput(4, visualCatalogPage("END OF GAME", "When a runner reaches the finish space, determine the winner and resolve ties.")));
        OutlineDraft incompleteEnding = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(3))));
        OutlineDraft completeEnding = new OutlineDraft(
                "Game", "Premise", List.of(
                        topicWithTags("setup", List.of("setup"), List.of(1)),
                        topicWithTags("play", List.of("core_loop"), List.of(2)),
                        topicWithTags("ending", List.of("end", "scoring"), List.of(4))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TeachingPlanService.validateVisualCoreTopicBindings(incompleteEnding, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end");
        TeachingPlanService.validateVisualCoreTopicBindings(completeEnding, pages);
    }

    @Test
    void rejectsAnOverlyFragmentedVisualPlanBeforeItCanExhaustTheBaseLessonBudget() {
        List<TopicDraft> topics = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> topic("topic-" + index, false, List.of(index)))
                .toList();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TeachingPlanService.validateVisualFastBaseline(new OutlineDraft("Game", "Premise", topics)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ten-section fast baseline");
    }

    @Test
    void keepsTheCompleteSourceDerivedPlanWhenCoverageWouldFragmentTheModelPlan() {
        OutlineDraft model = new OutlineDraft(
                "Game", "Premise", java.util.stream.IntStream.rangeClosed(1, 11)
                        .mapToObj(index -> topic("model-" + index, false, List.of(index)))
                        .toList());
        OutlineDraft source = new OutlineDraft(
                "Game", "Premise", List.of(
                        topic("setup", true, List.of(1, 2)),
                        topic("turn", false, List.of(3, 4)),
                        topic("end", false, List.of(5))));

        assertThat(TeachingPlanService.keepFastVisualBaseline(model, source)).isSameAs(source);
        assertThat(TeachingPlanService.keepFastVisualBaseline(source, model)).isSameAs(source);
    }

    @Test
    void prefersTheUserProvidedRulebookTitleOverAGenericModelTitle() {
        OutlineDraft preferred = TeachingPlanService.preferDocumentTitle(
                "My custom game title",
                new OutlineDraft("Imported rulebook", "Premise", List.of(topic("setup", false, List.of(1)))));

        assertThat(preferred.gameTitle()).isEqualTo("My custom game title");
        assertThat(preferred.topics()).hasSize(1);
    }

    @Test
    void rejectsAVisualOutlineThatLeavesSubstantiveSourcePagesUnbound() {
        OutlineDraft incomplete = new OutlineDraft("Game", "Premise", List.of(topic("setup", false, List.of(2))));
        List<PageInput> pages = List.of(
                new PageInput(1, visualCatalogPage("TEST GAME", "cover; no game mechanism")),
                new PageInput(2, visualCatalogPage("SET UP", "starting resources")),
                new PageInput(3, visualCatalogPage("ACTIONS", "action sequence")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TeachingPlanService.validateVisualRulebookCoverage(incomplete, pages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }

    @Test
    void preservesModelSpecificTopicsWhileAddingOnlyUncoveredVisualPages() {
        OutlineDraft model = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("specific-actions", false, List.of(2, 5))));
        OutlineDraft source = new OutlineDraft(
                "Game",
                "Premise",
                List.of(
                        topic("components", true, List.of(2, 3)),
                        topic("setup", true, List.of(4, 5))));

        OutlineDraft augmented = TeachingPlanService.augmentVisualCoverage(model, source);

        assertThat(augmented.topics()).hasSize(3);
        assertThat(augmented.topics().getFirst().key()).isEqualTo("specific-actions");
        assertThat(augmented.topics().get(1).sourcePageNumbers()).containsExactly(3);
        assertThat(augmented.topics().get(2).sourcePageNumbers()).containsExactly(4);
        assertThat(augmented.topics()).extracting(TopicDraft::key)
                .contains("source-coverage-2", "source-coverage-3");
    }

    @Test
    void mergesAnUncoveredPageIntoTheMatchingTopicInsteadOfDuplicatingItsChapter() {
        OutlineDraft model = new OutlineDraft(
                "Game",
                "Premise",
                List.of(new TopicDraft(
                        "actions-and-costs",
                        "行动、费用与结果",
                        "Teach the player the main action.",
                        true,
                        true,
                        List.of("action"),
                        List.of("core_loop"),
                        List.of(2, 3, 4, 5))));
        OutlineDraft source = new OutlineDraft(
                "Game",
                "Premise",
                List.of(new TopicDraft(
                        "actions-and-costs",
                        "行动、费用与结果",
                        "Source coverage for the main action.",
                        true,
                        true,
                        List.of("cost"),
                        List.of("actions"),
                        List.of(5, 6))));

        OutlineDraft augmented = TeachingPlanService.augmentVisualCoverage(model, source);

        assertThat(augmented.topics()).hasSize(1);
        assertThat(augmented.topics().getFirst().key()).isEqualTo("actions-and-costs");
        assertThat(augmented.topics().getFirst().sourcePageNumbers()).containsExactly(2, 3, 4, 5, 6);
        assertThat(augmented.topics().getFirst().retrievalQueries()).containsExactly("action", "cost");
        assertThat(augmented.topics().getFirst().coverageTags()).containsExactly("core_loop", "actions");
    }

    @Test
    void mergesDifferentlyNamedCompoundEndgameCoverageIntoTheExistingChapter() {
        OutlineDraft model = new OutlineDraft(
                "Game",
                "Premise",
                List.of(new TopicDraft(
                        "end-game-and-scoring",
                        "游戏结束与计分",
                        "Explain when the game ends and how to score it.",
                        true,
                        true,
                        List.of("end game", "scoring"),
                        List.of("end", "scoring"),
                        List.of(12))));
        OutlineDraft source = new OutlineDraft(
                "Game",
                "Premise",
                List.of(new TopicDraft(
                        "winner-and-tiebreaker",
                        "结束、计分与胜者",
                        "Cover final scoring and ties.",
                        true,
                        true,
                        List.of("winner", "tie"),
                        List.of("end", "scoring", "tie_breaker"),
                        List.of(13, 14))));

        OutlineDraft augmented = TeachingPlanService.augmentVisualCoverage(model, source);

        assertThat(augmented.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.key()).isEqualTo("end-game-and-scoring");
            assertThat(topic.sourcePageNumbers()).containsExactly(12, 13, 14);
            assertThat(topic.retrievalQueries()).containsExactly("end game", "scoring", "winner", "tie");
            assertThat(topic.coverageTags()).containsExactly("end", "scoring", "tie_breaker");
        });
    }

    private String visualCatalogPage(String terms, String facts) {
        return "[Visual page catalog; verify against page image]\nPrinted terms: "
                + terms
                + "\nVisible facts: "
                + facts
                + "\nKeywords: test";
    }

    private TopicDraft topic(String key, boolean visual, List<Integer> pages) {
        return new TopicDraft(
                key,
                key,
                "Teach " + key,
                true,
                visual,
                List.of(key),
                List.of("core_loop"),
                pages);
    }

    private TopicDraft detailedTopic(String key, String title, String objective) {
        return new TopicDraft(
                key,
                title,
                objective,
                true,
                false,
                List.of("rulebook term"),
                List.of("core_loop"),
                List.of(1));
    }

    private TopicDraft topicWithTags(String key, List<String> tags, List<Integer> pages) {
        return new TopicDraft(
                key,
                key,
                "Teach " + key,
                true,
                false,
                List.of(key),
                tags,
                pages);
    }

    private PageView page(int number, String text) {
        return new PageView(number, text, text.length());
    }
}
