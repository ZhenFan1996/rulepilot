package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.VisualRulebookPageFactSearch.PageFactMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerVisualFactPresentationPolicyTest {

    @Test
    void keepsMechanicalBindingsAndRemovesAppearanceOnlyAuditClauses() {
        PageFactMatch fact = new PageFactMatch(
                7,
                "A-01; B-02; C-03",
                "A-01: Gain one energy; the lower-space reward depicts a green bolt.\n"
                        + "B-02: Lower-space reward is one card, shown as a blue rectangle; upper space shows level 2.\n"
                        + "C-03: Gain the depicted reward: 2 points, as shown by a wreath pictogram.\n"
                        + "A #01 shows a reward of 1⚡.\n"
                        + "Every upper space gives 2 points.",
                List.of("A-01", "B-02", "C-03"),
                1.0);

        assertThat(AnswerVisualFactPresentationPolicy.evidenceText(fact))
                .contains(
                        "A-01: Gain one energy",
                        "B-02: Lower-space reward is one card",
                        "C-03: Gain 2 points",
                        "Every upper space gives 2 points")
                .doesNotContain(
                        "green bolt",
                        "blue rectangle",
                        "upper space shows level 2",
                        "A #01 shows",
                        "wreath pictogram",
                        "Printed terms");
    }
}
