package com.rulepilot.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ConstraintRangeTest {

    @Test
    void preservesBothBoundsStrengthAndPlayerEvidenceAsOneValue() {
        ConstraintRange<Integer> range = ConstraintRange.hard(120, 180, "  120–180 分钟，是硬条件。  ", 3);

        assertThat(range.minimum()).isEqualTo(120);
        assertThat(range.maximum()).isEqualTo(180);
        assertThat(range.strength()).isEqualTo(ConstraintRange.Strength.HARD);
        assertThat(range.sourceText()).isEqualTo("120–180 分钟，是硬条件。");
        assertThat(range.confirmedTurn()).isEqualTo(3);
        assertThat(range.contains(120)).isTrue();
        assertThat(range.contains(150)).isTrue();
        assertThat(range.contains(181)).isFalse();
    }

    @Test
    void preservesLongPlayerEvidenceInsteadOfTurningConversationLengthIntoAValidationFailure() {
        String evidence = "这是一条包含完整背景、排除项与桌上需求的玩家条件。".repeat(40);

        ConstraintRange<Integer> range = ConstraintRange.hard(3, 4, evidence, 12);

        assertThat(range.sourceText()).isEqualTo(evidence);
    }

    @Test
    void rejectsReversedEmptyAndOutOfContractMetadata() {
        assertThatThrownBy(() -> ConstraintRange.hard(180, 120, "范围", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");
        assertThatThrownBy(() -> new ConstraintRange<BigDecimal>(
                        null, null, ConstraintRange.Strength.HARD, "范围", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound");
        assertThatThrownBy(() -> ConstraintRange.hard(1, 2, "范围", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turn");
    }
}
