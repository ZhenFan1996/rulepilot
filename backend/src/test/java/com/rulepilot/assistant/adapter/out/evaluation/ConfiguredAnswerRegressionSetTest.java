package com.rulepilot.assistant.adapter.out.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfiguredAnswerRegressionSetTest {

    @Test
    void shipsWithoutAProductionRulebookRegressionCorpus() {
        var regressionSet = new ConfiguredAnswerRegressionSet("", "");

        assertThat(regressionSet.name()).isEqualTo("external-answer-regressions");
        assertThat(regressionSet.cases()).isEmpty();
    }

    @Test
    void loadsAnExplicitlyConfiguredDeveloperDataset() {
        var regressionSet = new ConfiguredAnswerRegressionSet(
                "classpath:evaluation/seti-answer-regressions-v1.json", "local-table-rulings");

        assertThat(regressionSet.name()).isEqualTo("local-table-rulings");
        assertThat(regressionSet.cases()).hasSize(3);
    }
}
