package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RulebookIconGlossaryServiceTest {

    @Test
    void keepsGlossaryGeneratingUntilTheVisualRunIsTerminal() {
        assertThat(RulebookIconGlossaryService.determineStatus(true, true, 8, 8, 8))
                .isEqualTo(RulebookIconGlossaryService.GlossaryStatus.GENERATING);
    }

    @Test
    void reportsReadyOnlyAfterEveryPageIsCompleteAndNoRunIsActive() {
        assertThat(RulebookIconGlossaryService.determineStatus(true, false, 8, 8, 8))
                .isEqualTo(RulebookIconGlossaryService.GlossaryStatus.READY);
    }

    @Test
    void preservesUnavailableAndNotStartedStates() {
        assertThat(RulebookIconGlossaryService.determineStatus(false, false, 0, 0, 8))
                .isEqualTo(RulebookIconGlossaryService.GlossaryStatus.UNAVAILABLE);
        assertThat(RulebookIconGlossaryService.determineStatus(true, false, 0, 0, 8))
                .isEqualTo(RulebookIconGlossaryService.GlossaryStatus.NOT_STARTED);
    }
}
