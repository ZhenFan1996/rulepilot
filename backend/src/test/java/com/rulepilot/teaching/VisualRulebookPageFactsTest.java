package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.rulepilot.teaching.VisualRuleGroupTestFacts.fact;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookPageFactsTest {

    @Test
    void completeRuleGroupInventoryRequiresEveryExactBoundFact() {
        assertThatThrownBy(() -> completeFact(
                        "Display prose is not a binding.",
                        List.of("MOVE", "BUILD"),
                        List.of(fact("MOVE", "Move one pawn."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
        assertThatThrownBy(() -> completeFact(
                        "MOVE appears in prose but cannot repair a mismatched typed identity.",
                        List.of("MOVE"),
                        List.of(fact("MOVEMENT", "Move one pawn."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
        assertThatThrownBy(() -> completeFact(
                        "MOVE: Move one pawn.",
                        List.of("MOVE"),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete rule-group inventory");
    }

    @Test
    void completeRuleGroupInventoryAcceptsOnlyExactTypedBoundFacts() {
        assertThatNoException().isThrownBy(() -> completeFact(
                "Player-facing factual summary can use different wording.",
                List.of("MOVE", "BUILD"),
                List.of(
                        fact("MOVE", "Move one pawn."),
                        fact("BUILD", "Place one building."))));
        assertThatNoException().isThrownBy(() -> completeFact(
                "This fully checked non-gameplay page owns no gameplay rule group.",
                List.of(),
                List.of()));
    }

    private static PageFact completeFact(
            String factualSummary,
            List<String> identifiers,
            List<RuleGroupFact> facts) {
        return new PageFact(
                4,
                "MOVE; BUILD",
                factualSummary,
                List.of("move", "build"),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                identifiers,
                true,
                facts);
    }
}
