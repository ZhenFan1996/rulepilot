package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.CoverageState;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulebookUnderstandingBuilderTest {

    private final RulebookUnderstandingBuilder builder = new RulebookUnderstandingBuilder();

    @Test
    void keepsPageCoordinatesReadingOrderAndHeadingOwnership() {
        var understanding = builder.build(List.of(new ExtractedPage(7, "ignored", List.of(
                new ExtractedTextBlock(0, "SETUP", 100, 90, 220, 32),
                new ExtractedTextBlock(1, "Give every player one Scout card.", 100, 150, 500, 44),
                new ExtractedTextBlock(2, "7", 480, 970, 20, 15)))));

        assertThat(understanding.pageBlocks()).extracting(block -> block.role())
                .containsExactly(BlockRole.HEADING, BlockRole.BODY, BlockRole.FOOTER);
        assertThat(understanding.pageBlocks().get(1).headingBlockIndex()).isEqualTo(0);
        assertThat(understanding.pageBlocks().get(1).rectangle().x()).isEqualTo(100);
        assertThat(understanding.inventory()).extracting(item -> item.key()).containsExactly("p7-b0", "p7-b1");
        assertThat(understanding.coverageLedger()).allMatch(entry -> entry.state() == CoverageState.UNPLANNED);
    }

    @Test
    void usesSourceHeadingsAsTermsWithoutGuessingTermsFromBodyCapitalization() {
        var understanding = builder.build(List.of(new ExtractedPage(2, "", List.of(
                new ExtractedTextBlock(0, "FIRST ROUND", 80, 100, 300, 40),
                new ExtractedTextBlock(1, "The Scout moves after a PLAYER action.", 80, 180, 600, 50)))));

        assertThat(understanding.terminology()).extracting(term -> term.term())
                .containsExactly("FIRST ROUND");
    }

    @Test
    void retainsAHeadingUpToThePersistedTerminologyColumnWidth() {
        String heading = "CONFIGURATION ".repeat(8).strip();

        var understanding = builder.build(List.of(new ExtractedPage(2, "", List.of(
                new ExtractedTextBlock(0, heading, 80, 100, 800, 40)))));

        assertThat(heading.length()).isBetween(101, 120);
        assertThat(understanding.terminology()).extracting(term -> term.term()).contains(heading.strip());
    }

    @Test
    void preservesTextOnlyExtractorsAsOneWholePageEvidenceBlock() {
        var understanding = builder.build(List.of(new ExtractedPage(3, "Scoring happens after the final round.")));

        assertThat(understanding.pageBlocks()).singleElement().satisfies(block -> {
            assertThat(block.rectangle().width()).isEqualTo(1_000);
            assertThat(block.rectangle().height()).isEqualTo(1_000);
        });
        assertThat(understanding.inventory()).hasSize(1);
    }

    @Test
    void retainsImageOnlyPagesAsWholePageVisualEvidence() {
        var understanding = builder.build(List.of(new ExtractedPage(6, "")));

        assertThat(understanding.pageBlocks()).singleElement().satisfies(block -> {
            assertThat(block.text()).isEqualTo(RulebookUnderstandingBuilder.VISUAL_PAGE_PLACEHOLDER);
            assertThat(block.rectangle().width()).isEqualTo(1_000);
            assertThat(block.rectangle().height()).isEqualTo(1_000);
        });
        assertThat(understanding.inventory()).extracting(item -> item.key()).containsExactly("p6-b0");
    }
}
