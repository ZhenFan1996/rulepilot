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
        assertThat(AnswerEvidencePolicy.isEndgameResolutionQuestion(
                        "如果我可以选择结束游戏，其他玩家还会继续玩吗？"))
                .isTrue();
    }

    @Test
    void recognisesOnlyEvidenceThatActuallyDescribesEndOfTurnProcedure() {
        EvidenceInput procedure = new EvidenceInput(
                UUID.randomUUID(),
                "TURN",
                "End of turn",
                "After your turn, reveal and resolve one event card.",
                4,
                4);

        assertThat(AnswerEvidencePolicy.hasEndTurnProcedure("After your turn, reveal and resolve one event card."))
                .isTrue();
        assertThat(AnswerEvidencePolicy.hasEndTurnProcedure("Draw cards at the beginning of the round.")).isFalse();
        assertThat(AnswerEvidencePolicy.requiresEndTurnProcedureCitation(
                        "After I finish my turn, do I reveal an event card?", java.util.List.of(procedure)))
                .isTrue();
        assertThat(AnswerEvidencePolicy.citesEndTurnProcedure(java.util.List.of(procedure), java.util.List.of()))
                .isFalse();
        assertThat(AnswerEvidencePolicy.citesEndTurnProcedure(
                        java.util.List.of(procedure), java.util.List.of(procedure.chunkId())))
                .isTrue();
    }

    @Test
    void requiresOneDirectEndgameSourceAndKeepsOnlyItsCitation() {
        EvidenceInput resolution = new EvidenceInput(
                UUID.randomUUID(),
                "ENDGAME",
                "Game end",
                "When the final round ends, the game ends. Players score points for their completed rows. "
                        + "On a tie, the player with more coins wins.",
                8,
                9);
        EvidenceInput peripheral = new EvidenceInput(
                UUID.randomUUID(), "SETUP", "Setup", "Place the round marker on the first space.", 2, 2);
        var evidence = java.util.List.of(resolution, peripheral);
        String question = "When does the game end, how do we score, and who wins a tie?";

        assertThat(AnswerEvidencePolicy.requiresEndgameResolutionCitation(question, evidence)).isTrue();
        assertThat(AnswerEvidencePolicy.citesEndgameResolution(
                        question, evidence, java.util.List.of(peripheral.chunkId())))
                .isFalse();
        assertThat(AnswerEvidencePolicy.citesEndgameResolution(
                        question, evidence, java.util.List.of(resolution.chunkId())))
                .isTrue();
        assertThat(AnswerEvidencePolicy.requiredEndgameCitationIds(
                        question,
                        evidence,
                        java.util.List.of(resolution.chunkId(), peripheral.chunkId())))
                .containsExactly(resolution.chunkId());
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
