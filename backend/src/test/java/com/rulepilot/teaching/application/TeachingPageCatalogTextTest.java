package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingPageCatalogTextTest {

    @Test
    void preservesOpaqueEvidenceFromTheBeginningMiddleAndEndOfEveryLongPage() {
        String source = "BEGIN-ANCHOR "
                + "A".repeat(3_000)
                + " MIDDLE-ANCHOR "
                + "B".repeat(3_000)
                + " END-ANCHOR";

        String catalog = TeachingPageCatalogText.bounded(source);

        assertThat(catalog)
                .hasSizeLessThanOrEqualTo(TeachingPageCatalogText.MAX_CHARACTERS)
                .contains("BEGIN-ANCHOR", "MIDDLE-ANCHOR", "END-ANCHOR")
                .contains("middle excerpt follows", "final excerpt follows");
    }

    @Test
    void leavesShortPagesIntactWithoutInventingOmissionMarkers() {
        String source = "  An unfamiliar procedure followed by an exact exception.  ";

        assertThat(TeachingPageCatalogText.bounded(source))
                .isEqualTo("An unfamiliar procedure followed by an exact exception.")
                .doesNotContain("omitted");
    }
}
