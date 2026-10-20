package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.PageView;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingPlanServiceVisualAvailabilityTest {

    @Test
    void renderedPagesAreAvailableWithoutAPreCutPictureIndex() {
        var pages = List.of(
                new PageView(1, "Setup", 5, true),
                new PageView(2, "Actions", 7, false));

        assertThat(TeachingPlanService.availableVisualPageNumbers(pages))
                .containsExactly(1);
    }

}
