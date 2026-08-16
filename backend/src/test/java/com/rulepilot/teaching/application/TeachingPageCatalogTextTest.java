package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeachingPageCatalogTextTest {

    @Test
    void preservesOpaqueEvidenceFromTheBeginningMiddleAndEndOfEveryLongPage() {
        String source = "BEGIN-ANCHOR "
                + "A ".repeat(3_500)
                + " MIDDLE-ANCHOR "
                + "B ".repeat(3_500)
                + " END-ANCHOR";

        String catalog = TeachingPageCatalogText.bounded(source);

        assertThat(catalog)
                .hasSizeLessThanOrEqualTo(TeachingPageCatalogText.MAX_CHARACTERS)
                .contains("BEGIN-ANCHOR", "MIDDLE-ANCHOR", "END-ANCHOR")
                .contains("middle excerpt follows", "final excerpt follows");
    }

    @Test
    void neverExposesAWordFragmentAsAnExactSourceIdentifierAtAnExcerptBoundary() {
        String source = "A ".repeat(516)
                + "REVEALTURN "
                + "B ".repeat(2_100)
                + "COMBATRESOLUTION "
                + "C ".repeat(900);

        String catalog = TeachingPageCatalogText.bounded(source);

        assertThat(catalog)
                .contains("REVEALTURN", "COMBATRESOLUTION")
                .doesNotMatch("(?s).*\\p{Alnum}… \\[omitted;.*")
                .doesNotMatch("(?s).*follows] …\\p{Alnum}.*");
    }

    @Test
    void leavesShortPagesIntactWithoutInventingOmissionMarkers() {
        String source = "  An unfamiliar procedure followed by an exact exception.  ";

        assertThat(TeachingPageCatalogText.bounded(source))
                .isEqualTo("An unfamiliar procedure followed by an exact exception.")
                .doesNotContain("omitted");
    }
}
