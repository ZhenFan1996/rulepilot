package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePageChunkerTest {

    private final RulePageChunker chunker = new RulePageChunker();

    @Test
    void preservesEveryPageAndNeverCreatesBroadPageRanges() {
        var chunks = chunker.chunk(List.of(
                new ExtractedPage(4, "SETUP\nPlace the map in the center.\n\nGive each player two cards."),
                new ExtractedPage(5, "YOUR TURN\nChoose one action, then draw a card.")));

        assertThat(chunks).extracting(chunk -> chunk.pageNumber()).containsExactly(4, 5);
        assertThat(chunks.getFirst().heading()).isEqualTo("SETUP");
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= RulePageChunker.MAX_CHUNK_CHARACTERS);
    }

    @Test
    void splitsLongPagesIntoRetrievalSizedChunks() {
        String paragraph = "Score one point. ".repeat(400);

        var chunks = chunker.chunk(List.of(new ExtractedPage(12, "SCORING\n\n" + paragraph)));

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> chunk.pageNumber() == 12);
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= RulePageChunker.MAX_CHUNK_CHARACTERS);
    }

    @Test
    void retainsImageOnlyPagesAsVisualEvidenceInsteadOfRejectingTheRulebook() {
        var chunks = chunker.chunk(List.of(new ExtractedPage(9, "")));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.heading()).isEqualTo("Visual rulebook page 9");
            assertThat(chunk.content()).isEqualTo(RulePageChunker.VISUAL_PAGE_PLACEHOLDER);
            assertThat(chunk.pageNumber()).isEqualTo(9);
        });
    }
}
