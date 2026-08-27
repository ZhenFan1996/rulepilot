package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePageChunkerTest {

    private final RulePageChunker chunker = new RulePageChunker();

    @Test
    void preservesEveryPageAndNeverCreatesBroadPageRanges() {
        var chunks = chunk(List.of(
                new ExtractedPage(4, "SETUP\nPlace the map in the center.\n\nGive each player two cards."),
                new ExtractedPage(5, "YOUR TURN\nChoose one action, then draw a card.")));

        assertThat(chunks).extracting(chunk -> chunk.pageNumber()).containsExactly(4, 5);
        assertThat(chunks.getFirst().heading()).isEqualTo("SETUP");
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= RulePageChunker.MAX_CHUNK_CHARACTERS);
    }

    @Test
    void splitsLongPagesIntoRetrievalSizedChunks() {
        String paragraph = "Score one point. ".repeat(400);

        var chunks = chunk(List.of(new ExtractedPage(12, "SCORING\n\n" + paragraph)));

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> chunk.pageNumber() == 12);
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= RulePageChunker.MAX_CHUNK_CHARACTERS);
    }

    @Test
    void bindsEachCompactPageSectionToItsOwnDocumentDerivedHeading() {
        var page = new ExtractedPage(4, """
                RULES OVERVIEW
                A player uses one personality card on a turn.
                TRIBUNE
                Recover the personality cards that were already played.
                ARCHITECT
                Move colonists, then build houses in eligible cities.
                MERCATOR
                Receive coins, then trade the listed goods.
                """, List.of(
                new ExtractedTextBlock(0, "RULES OVERVIEW", 50, 50, 300, 35),
                new ExtractedTextBlock(1, "A player uses one personality card on a turn.", 50, 95, 800, 24),
                new ExtractedTextBlock(2, "TRIBUNE", 50, 150, 180, 35),
                new ExtractedTextBlock(3, "Recover the personality cards that were already played.", 50, 195, 850, 24),
                new ExtractedTextBlock(4, "ARCHITECT", 50, 250, 200, 35),
                new ExtractedTextBlock(5, "Move colonists, then build houses in eligible cities.", 50, 295, 850, 24),
                new ExtractedTextBlock(6, "MERCATOR", 50, 350, 190, 35),
                new ExtractedTextBlock(7, "Receive coins, then trade the listed goods.", 50, 395, 800, 24)));
        var understanding = new RulebookUnderstandingBuilder().build(List.of(page));

        var chunks = chunker.chunk(List.of(page), understanding);

        assertThat(chunks).extracting(chunk -> chunk.heading())
                .containsExactly("RULES OVERVIEW", "TRIBUNE", "ARCHITECT", "MERCATOR");
        assertThat(chunks).zipSatisfy(
                List.of("uses one personality card", "Recover the personality cards", "Move colonists", "trade the listed goods"),
                (chunk, expectedText) -> assertThat(chunk.content()).contains(expectedText));
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("RULES OVERVIEW\nA player uses one personality card\n\nTRIBUNE"));
    }

    @Test
    void retainsNonEnglishRuleEvidenceEvenWhenLayoutHeuristicsMarkTheBottomBlockAsAFooter() {
        String bottomRule = "ラウンド終了時、勝利点を 2 点得る。";
        var page = new ExtractedPage(6, "得分\n" + bottomRule, List.of(
                new ExtractedTextBlock(0, "得分", 50, 80, 240, 40),
                new ExtractedTextBlock(1, bottomRule, 50, 950, 700, 24)));
        var understanding = new RulebookUnderstandingBuilder().build(List.of(page));

        assertThat(understanding.pageBlocks().getLast().role())
                .isEqualTo(com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole.FOOTER);

        var chunks = chunker.chunk(List.of(page), understanding);

        assertThat(chunks).extracting(chunk -> chunk.content()).anySatisfy(content -> assertThat(content).contains(bottomRule));
    }

    @Test
    void retainsImageOnlyPagesAsVisualEvidenceInsteadOfRejectingTheRulebook() {
        var chunks = chunk(List.of(new ExtractedPage(9, "")));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.heading()).isEqualTo("Visual rulebook page 9");
            assertThat(chunk.content()).isEqualTo(RulePageChunker.VISUAL_PAGE_PLACEHOLDER);
            assertThat(chunk.pageNumber()).isEqualTo(9);
        });
    }

    private List<RuleStructureRepository.DetectedRuleChunk> chunk(List<ExtractedPage> pages) {
        return chunker.chunk(pages, new RulebookUnderstandingBuilder().build(pages));
    }
}
