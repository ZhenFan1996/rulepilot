package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookPageFactsTest {

    @Test
    void completeRuleGroupInventoryRequiresEveryExactBoundFact() {
        assertThatThrownBy(() -> completeFact("MOVE: Move one pawn.", List.of("MOVE", "BUILD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
        assertThatThrownBy(() -> completeFact("MOVEMENT: Move one pawn.", List.of("MOVE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
        assertThatThrownBy(() -> completeFact("MOVE:", List.of("MOVE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
    }

    @Test
    void completeRuleGroupInventoryAcceptsNormalizedExactBoundFacts() {
        assertThatNoException().isThrownBy(() -> completeFact(
                "ＭＯＶＥ: Move one pawn.\nBUILD: Place one building.",
                List.of("MOVE", "BUILD")));
        assertThatNoException().isThrownBy(() -> completeFact(
                "This fully checked non-gameplay page owns no gameplay rule group.",
                List.of()));
    }

    private static PageFact completeFact(String factualSummary, List<String> identifiers) {
        return new PageFact(
                4,
                "MOVE; BUILD",
                factualSummary,
                List.of("move", "build"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                identifiers,
                true);
    }
}
