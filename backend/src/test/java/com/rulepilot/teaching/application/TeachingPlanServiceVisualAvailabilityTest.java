package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingPlanServiceVisualAvailabilityTest {

    @Test
    void renderedPagesRemainVisuallyAvailableWhenLocalProposalFallbackIsConfigured() {
        var pages = List.of(
                new PageView(1, "Setup", 5, true),
                new PageView(2, "Actions", 7, false));

        assertThat(TeachingPlanService.availableVisualPageNumbers(pages, true, List.of()))
                .containsExactly(1);
    }

    @Test
    void indexedPicturesStillRequireARenderedPageImageWhenLocalFallbackIsUnavailable() {
        var pages = List.of(
                new PageView(1, "Setup", 5, true),
                new PageView(2, "Actions", 7, false),
                new PageView(3, "Scoring", 7, true));
        var indexed = List.of(
                new Region(1, "PICTURE", 100, 100, 300, 300),
                new Region(2, "PICTURE", 100, 100, 300, 300),
                new Region(3, "TABLE", 100, 100, 300, 300));

        assertThat(TeachingPlanService.availableVisualPageNumbers(pages, false, indexed))
                .containsExactly(1);
    }
}
