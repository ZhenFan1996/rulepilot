package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import java.util.List;
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

    @Test
    void groundingIconEvidencePreservesTheCompleteSourceRuleGroupInventory() {
        PageFact fact = new PageFact(
                4,
                "MOVE",
                "MOVE: Move one pawn.",
                List.of("move"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("MOVE"),
                true,
                List.of(new RuleGroupFact("MOVE", "MOVE", "Move one pawn.")));

        PageFact grounded = RulebookIconGlossaryService.withGroundedIconEvidence(fact, "MOVE: Move one pawn.");

        assertThat(grounded.ruleGroupIdentifiers()).containsExactly("MOVE");
        assertThat(grounded.ruleGroupInventoryComplete()).isTrue();
    }
}
