package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisualRegionCandidateSelectorTest {

    @Test
    void matches_chinese_rulebook_phrases_when_the_lesson_uses_different_wording() {
        var understanding = new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        8, 0, 0, RulebookUnderstanding.BlockRole.BODY, "将探测器移动到行星轨道",
                        new RulebookUnderstanding.Rectangle(100, 200, 420, 120), null)),
                List.of(), List.of(), List.of());

        var selected = new VisualRegionCandidateSelector().select(
                understanding, Set.of(8), List.of("进入行星轨道的费用与操作"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber).containsOnly(8);
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::sourceText)
                .contains("Cited page 8 visual context");
    }

    @Test
    void selects_compact_term_matched_blocks_only_from_cited_pages() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(2, 0, BlockRole.BODY, "Place the probe on its orbit track", 100, 200, 300, 160),
                        block(2, 1, BlockRole.HEADING, "Probe movement", 80, 100, 250, 50),
                        block(3, 0, BlockRole.BODY, "Probe movement example", 100, 200, 300, 160),
                        block(2, 2, BlockRole.FOOTER, "Probe movement footer", 10, 950, 980, 30)),
                List.of(),
                List.of(),
                List.of());

        var candidates = new VisualRegionCandidateSelector().select(
                understanding, Set.of(2), List.of("move the probe on its orbit"));

        assertThat(candidates).extracting(VisualRegionCandidateSelector.Candidate::sourceText)
                .containsExactly("Cited page 2 visual context", "Place the probe on its orbit track", "Probe movement");
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.pageNumber()).isEqualTo(2));
    }

    @Test
    void reserves_both_relevant_cited_pages_as_visual_search_boundaries() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(2, 0, BlockRole.BODY, "Move the probe on its orbit", 100, 200, 300, 160),
                        block(3, 0, BlockRole.BODY, "Use the launch icon", 100, 200, 300, 160)),
                List.of(), List.of(), List.of());

        var candidates = new VisualRegionCandidateSelector().select(
                understanding, new LinkedHashSet<>(List.of(2, 3)), List.of("launch the probe icon"));

        assertThat(candidates).extracting(VisualRegionCandidateSelector.Candidate::sourceText)
                .contains("Cited page 2 visual context", "Cited page 3 visual context");
        assertThat(candidates).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsExactly(3, 2, 3, 2);
    }

    @Test
    void falls_back_to_cited_pages_when_rulebook_and_lesson_languages_differ() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(2, 0, BlockRole.BODY, "Place the probe", 100, 200, 300, 160),
                        block(2, 1, BlockRole.HEADING, "Probe movement", 80, 100, 250, 50),
                        block(3, 0, BlockRole.BODY, "An uncited example", 100, 200, 300, 160)),
                List.of(),
                List.of(),
                List.of());

        assertThat(new VisualRegionCandidateSelector().select(understanding, Set.of(2), List.of("探测器移动规则")))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.pageNumber()).isEqualTo(2);
                    assertThat(candidate.rectangle()).isEqualTo(new Rectangle(0, 0, 1_000, 1_000));
                    assertThat(candidate.sourceText()).isEqualTo("Cited page 2 visual context");
                });
    }

    @Test
    void covers_the_first_and_last_cited_page_when_translation_has_three_or_more_pages() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(4, 0, BlockRole.BODY, "Setup board", 100, 200, 300, 160),
                        block(5, 0, BlockRole.BODY, "Setup resources", 100, 200, 300, 160),
                        block(6, 0, BlockRole.BODY, "Setup player board", 100, 200, 300, 160)),
                List.of(), List.of(), List.of());

        var selected = new VisualRegionCandidateSelector().select(
                understanding, new LinkedHashSet<>(List.of(4, 5, 6)), List.of("玩家设置规则"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber).containsExactly(4, 6);
    }

    @Test
    void ranks_a_cited_page_with_a_matching_visual_catalog_hint_ahead_of_textually_opaque_pages() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(4, 0, BlockRole.BODY, "Set up the board", 100, 200, 300, 160),
                        block(5, 0, BlockRole.BODY, "Move the pieces", 100, 200, 300, 160),
                        block(6, 0, BlockRole.BODY, "Count the points", 100, 200, 300, 160)),
                List.of(), List.of(), List.of());
        List<PageFact> facts = List.of(
                new PageFact(4, "Setup", "一个空棋盘和起始组件", List.of("棋盘", "起始组件")),
                new PageFact(5, "Animal tokens", "六边形地块上的动物标记与相邻图标", List.of("动物标记", "六边形", "图标")),
                new PageFact(6, "Scoring", "末局计分表", List.of("计分", "分数表")));

        var selected = new VisualRegionCandidateSelector().select(
                understanding, new LinkedHashSet<>(List.of(4, 5, 6)), List.of("动物标记如何放到六边形地块"), facts);

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber).containsExactly(5, 4);
        assertThat(selected.getFirst().sourceText()).contains("Visual retrieval hint").contains("动物标记");
    }

    @Test
    void never_uses_a_visual_catalog_hint_from_an_uncited_page() {
        var understanding = new RulebookUnderstanding(
                List.of(block(4, 0, BlockRole.BODY, "Set up the board", 100, 200, 300, 160)),
                List.of(), List.of(), List.of());

        var selected = new VisualRegionCandidateSelector().select(
                understanding,
                Set.of(4),
                List.of("动物标记如何放到六边形地块"),
                List.of(new PageFact(5, "Animal tokens", "六边形地块上的动物标记", List.of("动物标记", "六边形"))));

        assertThat(selected).singleElement().satisfies(candidate -> {
            assertThat(candidate.pageNumber()).isEqualTo(4);
            assertThat(candidate.sourceText()).doesNotContain("Visual retrieval hint");
        });
    }

    @Test
    void prioritizes_a_matching_cataloged_visual_anchor_over_the_whole_cited_page() {
        var understanding = new RulebookUnderstanding(
                List.of(block(8, 0, BlockRole.BODY, "Score the animals", 0, 0, 1_000, 1_000)),
                List.of(), List.of(), List.of());
        PageFact facts = new PageFact(
                8,
                "Animal scoring",
                "本页包含多种动物的计分示例。",
                List.of("scoring"),
                List.of(
                        new VisualAnchor("score group", "Bear scoring", "熊卡牌旁的计分示例。", 80, 100, 280, 220),
                        new VisualAnchor("score group", "Fox scoring", "狐狸卡牌旁的计分示例。", 80, 470, 280, 220)));

        var selected = new VisualRegionCandidateSelector().select(
                understanding, Set.of(8), List.of("熊的计分示例"), List.of(facts));

        assertThat(selected.getFirst()).satisfies(candidate -> {
            assertThat(candidate.pageNumber()).isEqualTo(8);
            assertThat(candidate.rectangle()).isEqualTo(new Rectangle(80, 100, 280, 220));
            assertThat(candidate.sourceText()).contains("Cataloged visual anchor").contains("Bear scoring");
        });
        assertThat(selected).allSatisfy(candidate ->
                assertThat(candidate.sourceText()).doesNotContain("Cited page 8 visual context"));
    }

    @Test
    void does_not_use_a_cataloged_anchor_from_an_uncited_page() {
        var understanding = new RulebookUnderstanding(
                List.of(block(8, 0, BlockRole.BODY, "Score the animals", 0, 0, 1_000, 1_000)),
                List.of(), List.of(), List.of());
        PageFact uncitedFact = new PageFact(
                9,
                "Animal scoring",
                "熊的计分示例。",
                List.of("scoring"),
                List.of(new VisualAnchor("score group", "Bear scoring", "熊卡牌旁的计分示例。", 80, 100, 280, 220)));

        var selected = new VisualRegionCandidateSelector().select(
                understanding, Set.of(8), List.of("熊的计分示例"), List.of(uncitedFact));

        assertThat(selected).singleElement().satisfies(candidate -> {
            assertThat(candidate.pageNumber()).isEqualTo(8);
            assertThat(candidate.sourceText()).doesNotContain("Cataloged visual anchor");
        });
    }

    private PageBlock block(int page, int index, BlockRole role, String text, int x, int y, int width, int height) {
        return new PageBlock(page, index, index, role, text, new Rectangle(x, y, width, height), null);
    }
}
