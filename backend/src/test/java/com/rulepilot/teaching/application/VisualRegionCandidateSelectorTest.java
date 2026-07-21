package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import java.util.List;
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

        assertThat(selected).singleElement().extracting(VisualRegionCandidateSelector.Candidate::pageNumber).isEqualTo(8);
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
                .containsExactly("Place the probe on its orbit track", "Probe movement");
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.pageNumber()).isEqualTo(2));
    }

    @Test
    void falls_back_to_a_compact_cited_region_when_rulebook_and_lesson_languages_differ() {
        var understanding = new RulebookUnderstanding(
                List.of(
                        block(2, 0, BlockRole.BODY, "Place the probe", 100, 200, 300, 160),
                        block(2, 1, BlockRole.HEADING, "Probe movement", 80, 100, 250, 50)),
                List.of(),
                List.of(),
                List.of());

        assertThat(new VisualRegionCandidateSelector().select(understanding, Set.of(2), List.of("探测器移动规则")))
                .extracting(VisualRegionCandidateSelector.Candidate::sourceText)
                .containsExactly("Probe movement", "Place the probe");
    }

    private PageBlock block(int page, int index, BlockRole role, String text, int x, int y, int width, int height) {
        return new PageBlock(page, index, index, role, text, new Rectangle(x, y, width, height), null);
    }
}
