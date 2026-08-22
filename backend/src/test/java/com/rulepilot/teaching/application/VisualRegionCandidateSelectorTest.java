package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisualRegionCandidateSelectorTest {

    private final VisualRegionCandidateSelector selector = new VisualRegionCandidateSelector();
    private final RulebookUnderstanding understanding =
            new RulebookUnderstanding(List.of(), List.of(), List.of(), List.of());

    @Test
    void selectionIsStableAcrossNaturalPhrasingAndCatalogVocabulary() {
        Set<Integer> citedPages = new LinkedHashSet<>(List.of(4, 5));
        List<PageFact> catalog = List.of(
                new PageFact(4, "Setup", "一个空棋盘和起始组件", List.of("棋盘")),
                new PageFact(5, "Scoring", "末局计分表", List.of("计分")));

        var chinese = selector.select(understanding, citedPages, List.of("动物标记如何放置"), catalog);
        var unrelatedEnglish = selector.select(understanding, citedPages, List.of("completely different prose"), catalog);

        assertThat(chinese).isEqualTo(unrelatedEnglish);
        assertThat(chinese).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsExactly(4, 5);
        assertThat(chinese).allSatisfy(candidate -> {
            assertThat(candidate.rectangle()).isEqualTo(new Rectangle(0, 0, 1_000, 1_000));
            assertThat(candidate.catalogedAnchor()).isNull();
        });
    }

    @Test
    void keepsTheFirstAndLastTypedSourcePageWhenAClaimSpansMoreThanTwoPages() {
        var selected = selector.select(
                understanding,
                new LinkedHashSet<>(List.of(4, 5, 6, 7)),
                List.of("free player-facing lesson prose"));

        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                .containsExactly(4, 7);
        assertThat(selected).extracting(VisualRegionCandidateSelector.Candidate::sourceText)
                .containsExactly("Cited page 4 visual context", "Cited page 7 visual context");
    }

    @Test
    void neverInventsACandidateWithoutATypedCitedPage() {
        assertThat(selector.select(understanding, Set.of(), List.of("page nine perhaps"))).isEmpty();
    }
}
