package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.PageBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionProposer.Proposal;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisualRegionCandidateSelectorTest {

    private final VisualRegionCandidateSelector selector = new VisualRegionCandidateSelector();

    @Test
    void selectionAndOpaqueIdsAreStableAcrossUnrelatedNaturalPhrasing() {
        RulebookUnderstanding understanding = understanding(List.of(
                block(4, 0, 0, BlockRole.HEADING, "Setup", new Rectangle(40, 60, 300, 80)),
                block(4, 1, 1, BlockRole.BODY, "Place the pieces", new Rectangle(40, 160, 430, 220))));
        var chinese = selector.select(understanding, Set.of(4), List.of("动物标记如何放置"));
        var unrelated = selector.select(understanding, Set.of(4), List.of("completely different prose"));

        assertThat(chinese).isEqualTo(unrelated);
        assertThat(chinese).extracting(VisualRegionCandidateSelector.Candidate::candidateId)
                .allMatch(id -> id.matches("vc_[0-9a-f]{24}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void keepsNativeBlocksAndBoundedCoverageForAnAdjacentFigureWithoutReadingTheText() {
        Rectangle heading = new Rectangle(80, 60, 520, 90);
        Rectangle body = new Rectangle(80, 180, 700, 320);
        RulebookUnderstanding understanding = understanding(List.of(
                block(2, 2, 2, BlockRole.FOOTER, "2", new Rectangle(480, 960, 20, 20)),
                block(2, 1, 1, BlockRole.BODY, "Detailed rule", body),
                block(2, 0, 0, BlockRole.HEADING, "Round", heading)));

        var selected = selector.select(understanding, Set.of(2), List.of("unused lesson wording"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::rectangle)
                .containsExactly(
                        new Rectangle(0, 0, 550, 550),
                        heading,
                        new Rectangle(450, 0, 550, 550),
                        body,
                        new Rectangle(0, 450, 550, 550),
                        new Rectangle(450, 450, 550, 550));
        assertThat(selected.stream().map(VisualRegionCandidateSelector.Candidate::rectangle))
                .anySatisfy(rectangle -> assertThat(rectangle.x() + rectangle.width()).isEqualTo(1_000));
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::sourceKind)
                .containsOnly(VisualSourceKind.PAGE_REGION);
    }

    @Test
    void usesFourOverlappingDeterministicTilesWhenNoLocalLayoutExists() {
        RulebookUnderstanding scan = understanding(List.of(block(
                7,
                0,
                0,
                BlockRole.BODY,
                "A generic page-level extraction without local geometry",
                new Rectangle(0, 0, 1_000, 1_000))));

        var selected = selector.select(scan, Set.of(7), List.of("must not affect geometry"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::rectangle)
                .containsExactly(
                        new Rectangle(0, 0, 550, 550),
                        new Rectangle(450, 0, 550, 550),
                        new Rectangle(0, 450, 550, 550),
                        new Rectangle(450, 450, 550, 550));
    }

    @Test
    void givesPixelToolGeometryAnOpaqueCandidateWithoutLettingItHideIndependentCoverage() {
        Rectangle detectedDiagram = new Rectangle(225, 310, 280, 190);
        Rectangle nativeText = new Rectangle(70, 100, 760, 120);
        RulebookUnderstanding understanding = understanding(List.of(
                block(3, 0, 0, BlockRole.BODY, "prose above a component diagram", nativeText)));

        var selected = selector.select(
                understanding,
                Set.of(3),
                List.of("model prose is validation-only"),
                Map.of(3, List.of(new Proposal(detectedDiagram))));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::rectangle)
                .startsWith(
                        detectedDiagram,
                        new Rectangle(0, 0, 550, 550),
                        nativeText)
                .contains(new Rectangle(450, 450, 550, 550));
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::candidateId)
                .doesNotHaveDuplicates();
    }

    @Test
    void ordersTheCompleteFiniteCandidateListFairlyAcrossPages() {
        RulebookUnderstanding scan = understanding(List.of());

        var selected = selector.select(
                scan,
                new LinkedHashSet<>(List.of(4, 5, 6, 7)),
                List.of("free player-facing lesson prose"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsExactly(4, 5, 6, 7, 4, 5, 6, 7, 4, 5, 6, 7, 4, 5, 6, 7);
    }

    @Test
    void preservesBothLayoutAndGenericCoverageAcrossTheCompleteMultiPageList() {
        RulebookUnderstanding understanding = understanding(List.of(
                block(1, 0, 0, BlockRole.BODY, "first", new Rectangle(60, 90, 400, 180)),
                block(2, 0, 0, BlockRole.BODY, "second", new Rectangle(70, 100, 410, 190)),
                block(3, 0, 0, BlockRole.BODY, "third", new Rectangle(80, 110, 420, 200))));

        var selected = selector.select(understanding, Set.of(1, 2, 3), List.of("ignored"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .startsWith(1, 2, 3, 1, 2, 3)
                .containsOnly(1, 2, 3);
        assertThat(selected).hasSize(15);
        for (int page = 1; page <= 3; page++) {
            int currentPage = page;
            assertThat(selected.stream()
                            .filter(candidate -> candidate.pageNumber() == currentPage)
                            .map(VisualRegionCandidateSelector.Candidate::rectangle))
                    .contains(new Rectangle(0, 0, 550, 550))
                    .anyMatch(rectangle -> rectangle.x() > 0 || rectangle.y() > 0);
        }
    }

    @Test
    void keepsEveryCitedPageForLaterBoundedTransportBatches() {
        var selected = selector.select(
                understanding(List.of()),
                new LinkedHashSet<>(List.of(1, 2, 3, 4, 5, 6, 7)),
                List.of());

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsOnly(1, 2, 3, 4, 5, 6, 7);
        assertThat(selected).hasSize(28);
    }

    @Test
    void neverInventsACandidateWithoutATypedCitedPage() {
        assertThat(selector.select(understanding(List.of()), Set.of(), List.of("page nine perhaps"))).isEmpty();
    }

    private RulebookUnderstanding understanding(List<PageBlock> blocks) {
        return new RulebookUnderstanding(blocks, List.of(), List.of(), List.of());
    }

    private PageBlock block(
            int page,
            int blockIndex,
            int readingOrder,
            BlockRole role,
            String text,
            Rectangle rectangle) {
        return new PageBlock(page, blockIndex, readingOrder, role, text, rectangle, null);
    }
}
