package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BoundedRuleCalculatorTest {

    private final BoundedRuleCalculator calculator = new BoundedRuleCalculator();

    @Test
    void evaluatesBoundedRuleArithmeticDeterministically() {
        assertThat(calculator.evaluate("floor(8 / 3) * 5").result()).isEqualTo("10");
        assertThat(calculator.evaluate("max(2, 7) - 1").result()).isEqualTo("6");
        assertThat(calculator.evaluate("ceil(7 / 2)").result()).isEqualTo("4");
    }

    @Test
    void rejectsCodeUnknownFunctionsDivisionByZeroAndExcessiveMagnitude() {
        assertThatThrownBy(() -> calculator.evaluate("java.lang.Runtime.getRuntime()"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.evaluate("sqrt(9)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.evaluate("max(9)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.evaluate("8 / 0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.evaluate("1000000000000 * 2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
