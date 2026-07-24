package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogPolicyTest {

    @Test
    void pairsEveryIconTargetWithItsLegendAndKeepsTheLegendFirst() {
        assertThat(VisualRulebookCatalogPolicy.crossPageIconBatches(List.of(2, 5, 7), 5))
                .containsExactly(
                        List.of(5),
                        List.of(5, 2),
                        List.of(5, 7));
        assertThat(VisualRulebookCatalogPolicy.crossPageIconBatches(List.of(5), 5))
                .containsExactly(List.of(5));
    }
}
