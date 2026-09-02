package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
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
        RulebookUnderstanding understanding = understanding();
        Map<Integer, List<Proposal>> proposals = Map.of(
                4, List.of(new Proposal(new Rectangle(40, 60, 300, 180))));
        var chinese = selector.select(understanding, Set.of(4), List.of("动物标记如何放置"), proposals);
        var unrelated = selector.select(understanding, Set.of(4), List.of("completely different prose"), proposals);

        assertThat(chinese).isEqualTo(unrelated);
        assertThat(chinese).extracting(VisualRegionCandidateSelector.Candidate::candidateId)
                .allMatch(id -> id.matches("vc_[0-9a-f]{24}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void doesNotPublishTextBlocksOrCoarseQuadrantsWhenPixelGeometryIsUnavailable() {
        RulebookUnderstanding understanding = new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        7,
                        0,
                        0,
                        RulebookUnderstanding.BlockRole.BODY,
                        "A generic page-level extraction without visual-object geometry",
                        new Rectangle(80, 120, 760, 600),
                        null)),
                List.of(),
                List.of(),
                List.of());

        assertThat(selector.select(understanding, Set.of(7), List.of("must not affect geometry")))
                .isEmpty();
    }

    @Test
    void publishesOnlyExactPixelToolGeometryAndRemovesDuplicates() {
        Rectangle detectedDiagram = new Rectangle(225, 310, 280, 190);
        Rectangle nativeText = new Rectangle(70, 100, 760, 120);
        RulebookUnderstanding understanding = new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        3,
                        0,
                        0,
                        RulebookUnderstanding.BlockRole.BODY,
                        "prose above a component diagram",
                        nativeText,
                        null)),
                List.of(),
                List.of(),
                List.of());

        var selected = selector.select(
                understanding,
                Set.of(3),
                List.of("model prose is validation-only"),
                Map.of(3, List.of(new Proposal(detectedDiagram), new Proposal(detectedDiagram))));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::rectangle)
                .containsExactly(detectedDiagram)
                .doesNotContain(nativeText);
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::sourceKind)
                .containsOnly(VisualSourceKind.PAGE_REGION);
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::candidateId)
                .doesNotHaveDuplicates();
    }

    @Test
    void ordersTheCompleteFiniteCandidateListFairlyAcrossPages() {
        RulebookUnderstanding scan = understanding();
        Map<Integer, List<Proposal>> proposals = Map.of(
                4, List.of(new Proposal(new Rectangle(40, 40, 100, 100)), new Proposal(new Rectangle(50, 200, 100, 100))),
                5, List.of(new Proposal(new Rectangle(140, 40, 100, 100)), new Proposal(new Rectangle(150, 200, 100, 100))),
                6, List.of(new Proposal(new Rectangle(240, 40, 100, 100)), new Proposal(new Rectangle(250, 200, 100, 100))),
                7, List.of(new Proposal(new Rectangle(340, 40, 100, 100)), new Proposal(new Rectangle(350, 200, 100, 100))));

        var selected = selector.select(
                scan,
                new LinkedHashSet<>(List.of(4, 5, 6, 7)),
                List.of("free player-facing lesson prose"),
                proposals);

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsExactly(4, 5, 6, 7, 4, 5, 6, 7);
    }

    @Test
    void keepsEveryCitedPageForLaterBoundedTransportBatches() {
        Map<Integer, List<Proposal>> proposals = java.util.stream.IntStream.rangeClosed(1, 7)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                        page -> page,
                        page -> List.of(new Proposal(new Rectangle(page * 20, 40, 100, 100)))));
        var selected = selector.select(
                understanding(),
                new LinkedHashSet<>(List.of(1, 2, 3, 4, 5, 6, 7)),
                List.of(),
                proposals);

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsOnly(1, 2, 3, 4, 5, 6, 7);
        assertThat(selected).hasSize(7);
    }

    @Test
    void neverInventsACandidateWithoutATypedCitedPage() {
        assertThat(selector.select(understanding(), Set.of(), List.of("page nine perhaps"))).isEmpty();
    }

    private RulebookUnderstanding understanding() {
        return new RulebookUnderstanding(List.of(), List.of(), List.of(), List.of());
    }
}
