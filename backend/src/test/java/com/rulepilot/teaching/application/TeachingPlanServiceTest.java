package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
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

    private PageView page(int number, String text) {
        return new PageView(number, text, text.length());
    }
}
