package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidencePolicyTest {

    @Test
    void keepsEndgameResolutionBoundToAnActualTriggerRatherThanAComponentList() {
        EvidenceInput components = new EvidenceInput(
                UUID.randomUUID(), "COMPONENTS", "Components", "End game marker and score tokens.", 2, 2);
        EvidenceInput trigger = new EvidenceInput(
                UUID.randomUUID(), "ENDGAME", "Ending the game", "When the final round ends, the game ends.", 8, 8);

        assertThat(AnswerEvidencePolicy.hasEndgameResolution(components)).isFalse();
        assertThat(AnswerEvidencePolicy.hasEndgameResolution(trigger)).isTrue();
        assertThat(AnswerEvidencePolicy.isEndgameResolutionQuestion("When does the game end and how is the winner scored?"))
                .isTrue();
    }

    @Test
    void recognisesOnlyEvidenceThatActuallyDescribesEndOfTurnProcedure() {
        assertThat(AnswerEvidencePolicy.hasEndTurnProcedure("After your turn, reveal and resolve one event card."))
                .isTrue();
        assertThat(AnswerEvidencePolicy.hasEndTurnProcedure("Draw cards at the beginning of the round.")).isFalse();
    }

    @Test
    void keepsVisualPlaceholdersOutOfTextEvidenceSelectionAndExpandsChineseQueries() {
        RuleEvidenceHit placeholder = new RuleEvidenceHit(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RULES",
                "Visual page",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                3,
                3,
                0.1);

        assertThat(AnswerEvidencePolicy.isVisualPlaceholder(new HybridEvidenceHit(placeholder, 0.1, 1, null, false)))
                .isTrue();
        assertThat(AnswerEvidencePolicy.requiresCrossLanguageExpansion("这个图标表示什么？")).isTrue();
        assertThat(AnswerEvidencePolicy.requiresCrossLanguageExpansion("one icon")).isFalse();
    }
}
