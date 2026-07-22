package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
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
    void prioritizesTheIconLegendWithoutExceedingTheFourPageTopicLimit() {
        OutlineDraft outline = new OutlineDraft(
                "Game",
                "Premise",
                List.of(topic("wager", false, List.of(14, 15, 16, 17))));
        List<PageView> pages = List.of(
                page(3, "Components: 36 energy markers, 76 score tokens, 12 map tiles, and 40 cards."),
                page(7, "Setting up: Give each player energy markers placed behind the screen and one score token placed in front."),
                page(14, "Use this wager only if you have at least 2  . Place 2  on it."));

        OutlineDraft bound = TeachingPlanService.bindIconLegendEvidence(outline, pages);

        assertThat(bound.topics().getFirst().sourcePageNumbers()).containsExactly(14, 15, 16, 7);
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
    void prioritizesDirectVisualCoreEvidenceWithoutExceedingTheFourPageTopicLimit() {
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

        assertThat(repaired.topics().get(2).sourcePageNumbers()).containsExactly(4, 5, 6, 7);
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
    void restoresASourcePageDisplacedByNewDirectCoreEvidenceAsASupplementaryTopic() {
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

        assertThat(restored.topics()).last().satisfies(topic -> {
            assertThat(topic.sourcePageNumbers()).containsExactly(8);
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
