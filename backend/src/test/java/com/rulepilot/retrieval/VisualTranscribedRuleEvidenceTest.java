package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VisualTranscribedRuleEvidenceTest {

    @Test
    void marksOnlyBoundedAtomicRuleStatementsAsVisualTranscriptionEvidence() {
        String evidence = VisualTranscribedRuleEvidence.render(
                "Take every tile of one color, then move the remaining tiles to the center.");

        assertThat(VisualTranscribedRuleEvidence.contains(evidence)).isTrue();
        assertThat(evidence).contains("Visible rule facts:", "remaining tiles to the center");
        assertThat(VisualTranscribedRuleEvidence.contains("Cataloged visual anchors: board center")).isFalse();
        assertThatThrownBy(() -> VisualTranscribedRuleEvidence.render(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
