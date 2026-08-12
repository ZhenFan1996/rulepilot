package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressiveVisualTeachingPlanPolicyTest {

    @Test
    void startsWithTheModelSelectedEvidencePageAndKeepsEveryGameplayObligation() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.NON_GAMEPLAY, "", List.of(), List.of()),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "Setup", List.of("market"), List.of("setup")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "Turn", List.of("take cards"), List.of("core_loop")),
                        page(4, TeachingPageRole.GAMEPLAY_RULES, "Refill", List.of("refill"), List.of("source_coverage")),
                        page(5, TeachingPageRole.UNCERTAIN, "", List.of(), List.of()),
                        page(6, TeachingPageRole.GAMEPLAY_RULES, "End", List.of("end"), List.of("end")),
                        page(7, TeachingPageRole.GAMEPLAY_RULES, "Scoring", List.of("score"), List.of("scoring"))),
                facts(4, "Refill market", "回合结束后，每列市场补到两个可见卡牌。", "refill"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline(
                "Example game", pages(7), start);

        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers().getFirst())
                .containsExactly(4, 2, 3, 6, 7, 5);
        assertThat(outline.topics().getFirst()).satisfies(topic -> {
            assertThat(topic.title()).isEqualTo("Refill");
            assertThat(topic.retrievalQueries()).contains("refill");
            assertThat(topic.sourcePageNumbers()).containsExactly(4);
        });
        assertThat(outline.topics()).flatExtracting(topic -> topic.coverageTags())
                .contains("setup", "core_loop", "end", "scoring");
        assertThat(outline.topics().getLast().required()).isFalse();
        assertThat(outline.topics()).noneSatisfy(topic -> assertThat(topic.sourcePageNumbers()).contains(1));
    }

    @Test
    void pageVocabularyCannotOverrideTheStructuredRoleOrSelectedBinding() {
        var start = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.NON_GAMEPLAY, "FINAL SCORE WINNER", List.of(), List.of()),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "QXZ", List.of("alpha"), List.of("setup")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "Beta", List.of("beta"), List.of("core_loop")),
                        page(4, TeachingPageRole.GAMEPLAY_RULES, "Gamma", List.of("gamma"), List.of("end", "scoring"))),
                facts(3, "Beta", "当前玩家先执行可见动作，再结束本回合。", "beta"));

        var outline = ProgressiveVisualTeachingPlanPolicy.outline("Opaque game", pages(4), start);

        assertThat(outline.topics()).extracting(topic -> topic.sourcePageNumbers().getFirst())
                .containsExactly(3, 2, 4);
        assertThat(outline.topics()).noneSatisfy(topic -> assertThat(topic.sourcePageNumbers()).contains(1));
    }

    @Test
    void rejectsIncompleteBindingsAndMissingCoreCoverageInsteadOfGuessing() {
        var missingPage = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"), List.of("setup", "core_loop")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "C", List.of("c"), List.of("end", "scoring"))),
                facts(1, "A", "每位玩家拿取一个组件并放在自己面前。", "a"));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(3), missingPage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every supplied page exactly");

        var missingScoring = new ProgressiveTeachingStartDraft(
                List.of(
                        page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"), List.of("setup")),
                        page(2, TeachingPageRole.GAMEPLAY_RULES, "B", List.of("b"), List.of("core_loop")),
                        page(3, TeachingPageRole.GAMEPLAY_RULES, "C", List.of("c"), List.of("end"))),
                facts(2, "B", "当前玩家必须执行一个可见动作，然后结束回合。", "b"));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(3), missingScoring))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("core learning obligation");
    }

    @Test
    void rejectsASelectedNonGameplayOrEmptyFactPage() {
        assertThatThrownBy(() -> new ProgressiveTeachingStartDraft(
                        List.of(page(1, TeachingPageRole.NON_GAMEPLAY, "Cover", List.of(), List.of())),
                        facts(1, "Cover", "这里只显示游戏名称和出版社标志。", "cover")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-gameplay");

        var emptyFacts = new ProgressiveTeachingStartDraft(
                List.of(page(1, TeachingPageRole.GAMEPLAY_RULES, "A", List.of("a"),
                        List.of("setup", "core_loop", "end", "scoring"))),
                new PageSummary(1, null, null, null));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), emptyFacts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("facts are insufficient");
    }

    @Test
    void rejectsDefaultRetrievalKeywordsAndMoreThanFourAtomicFacts() {
        List<TeachingPageSketch> sketches = List.of(page(
                1,
                TeachingPageRole.GAMEPLAY_RULES,
                "Turn",
                List.of("take"),
                List.of("setup", "core_loop", "end", "scoring")));
        var defaultKeyword = new ProgressiveTeachingStartDraft(
                sketches,
                new PageSummary(1, "TAKE", "当前玩家必须拿取一个可见组件，然后结束当前回合。", null));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), defaultKeyword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("facts are insufficient");

        var tooManyFacts = new ProgressiveTeachingStartDraft(
                sketches,
                new PageSummary(
                        1,
                        "TAKE; REFILL",
                        "当前玩家拿取一个组件。\n然后补充市场。\n检查结束条件。\n计算本轮分数。\n再移动起始玩家标记。",
                        List.of("TAKE", "REFILL")));
        assertThatThrownBy(() -> ProgressiveVisualTeachingPlanPolicy.outline("Game", pages(1), tooManyFacts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("facts are insufficient");
    }

    private TeachingPageSketch page(
            int number,
            TeachingPageRole role,
            String heading,
            List<String> terms,
            List<String> tags) {
        return new TeachingPageSketch(number, role, heading, terms, tags);
    }

    private PageSummary facts(int number, String terms, String summary, String keyword) {
        return new PageSummary(number, terms, summary, List.of(keyword, "rule"));
    }

    private List<PageView> pages(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(page -> new PageView(page, "", 0))
                .toList();
    }
}
