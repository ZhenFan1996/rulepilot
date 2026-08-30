package com.rulepilot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuleAnsweringCalculationTest {

    @Test
    void preservesCompleteCalculationTextAtThePublicBoundary() {
        String expression = "(" + "resource_total + ".repeat(20) + "final_resource) / set_size";
        String result = "10 points, including " + "a documented conditional modifier; ".repeat(4) + "final modifier";

        RuleAnswering.Calculation calculation =
                new RuleAnswering.Calculation("  " + expression + "  ", "  " + result + "  ");

        assertThat(calculation.expression()).isEqualTo(expression);
        assertThat(calculation.result()).isEqualTo(result);
    }
}
