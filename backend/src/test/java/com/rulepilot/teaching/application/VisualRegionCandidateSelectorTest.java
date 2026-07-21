package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.domain.RulebookUnderstanding;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.domain.RulebookUnderstanding.Rectangle;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisualRegionCandidateSelectorTest {

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
    void returns_no_candidate_when_terms_do_not_match_cited_evidence() {
        var understanding = new RulebookUnderstanding(
                List.of(block(2, 0, BlockRole.BODY, "Place the probe", 100, 200, 300, 160)),
                List.of(),
                List.of(),
                List.of());

        assertThat(new VisualRegionCandidateSelector().select(understanding, Set.of(2), List.of("scoring points")))
                .isEmpty();
    }

    private PageBlock block(int page, int index, BlockRole role, String text, int x, int y, int width, int height) {
        return new PageBlock(page, index, index, role, text, new Rectangle(x, y, width, height), null);
    }
}
