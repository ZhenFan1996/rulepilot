package com.rulepilot.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuleCalculationTest {

    @Test
    void preservesCompleteVerifiedCalculationText() {
        String expression = "(" + "resource_total + ".repeat(20) + "final_resource) / set_size";
        String result = "10 points, including " + "a documented conditional modifier; ".repeat(4) + "final modifier";

        RuleCalculation calculation = new RuleCalculation("  " + expression + "  ", "  " + result + "  ");

        assertThat(calculation.expression()).isEqualTo(expression);
        assertThat(calculation.result()).isEqualTo(result);
    }
}
