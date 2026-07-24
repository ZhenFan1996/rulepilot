package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftPublicationPolicyTest {

    @Test
    void preservesOnlyDecisiveEndgameEvidenceAndLabelsAStatedTableApplication() {
        UUID resolutionId = UUID.randomUUID();
        UUID peripheralId = UUID.randomUUID();
        var request = request(
                "I just reached the final round. When does the game end, how do we score, and who wins a tie?",
                new RuleAnswerModel.EvidenceInput(
                        resolutionId,
                        "ENDGAME",
                        "Ending the game",
                        "When the final round ends, the game ends. Players score points for completed rows. "
                                + "On a tie, the player with more coins wins.",
                        8,
                        9),
                new RuleAnswerModel.EvidenceInput(
                        peripheralId, "SETUP", "Setup", "Place the marker on the first space.", 2, 2));
        var draft = draft(List.of(resolutionId, peripheralId));

        var prepared = AnswerDraftPublicationPolicy.prepare(request, draft);

        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.draft().citationIds()).containsExactly(resolutionId);
        assertThat(prepared.draft().answerBasis()).isEqualTo("GROUNDED_APPLICATION");
    }

    @Test
    void rejectsAnEndOfTurnAnswerThatOmitsItsDirectProcedureCitation() {
        UUID procedureId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        var request = request(
                "After I finish my turn, do I reveal and resolve an event card?",
                new RuleAnswerModel.EvidenceInput(
                        procedureId,
                        "TURN",
                        "End of turn",
                        "After your turn, reveal and resolve one event card.",
                        4,
                        4),
                new RuleAnswerModel.EvidenceInput(otherId, "ACTIONS", "Actions", "Choose one action.", 3, 3));

        var prepared = AnswerDraftPublicationPolicy.prepare(request, draft(List.of(otherId)));

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.failureMessage()).isEqualTo("回答没有引用回合结束处理的直接规则依据。");
    }

    @Test
    void rejectsAnEndgameSummaryThatOmitsTheDirectEndgameResolution() {
        UUID resolutionId = UUID.randomUUID();
        UUID peripheralId = UUID.randomUUID();
        var request = request(
                "When does the game end, how do we score, and who wins a tie?",
                new RuleAnswerModel.EvidenceInput(
                        resolutionId,
                        "ENDGAME",
                        "Ending the game",
                        "When the final round ends, the game ends. Players score points for completed rows. "
                                + "On a tie, the player with more coins wins.",
                        8,
                        9),
                new RuleAnswerModel.EvidenceInput(
                        peripheralId, "SETUP", "Setup", "Place the marker on the first space.", 2, 2));

        var prepared = AnswerDraftPublicationPolicy.prepare(request, draft(List.of(peripheralId)));

        assertThat(prepared.ready()).isFalse();
        assertThat(prepared.failureMessage()).isEqualTo("回答没有引用游戏结束结算的直接规则依据。");
    }

    @Test
    void addsTheReferencedLegendPageForAnAlreadyValidatedVisualMapping() {
        UUID operationalId = UUID.randomUUID();
        UUID legendId = UUID.randomUUID();
        var request = request(
                "What resource does this icon represent?",
                new RuleAnswerModel.EvidenceInput(
                        operationalId,
                        "RULES",
                        "Operation",
                        "The operational icon is visually identical to the same icon labeled 'Energy token' on page 5.",
                        2,
                        2),
                new RuleAnswerModel.EvidenceInput(
                        legendId, "COMPONENTS", "Legend", "Energy token is listed in the components legend.", 5, 5));

        var prepared = AnswerDraftPublicationPolicy.prepare(request, draft(List.of(operationalId)));

        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.draft().citationIds()).containsExactly(operationalId, legendId);
    }

    private RuleAnswerModel.ModelRequest request(String question, RuleAnswerModel.EvidenceInput... evidence) {
        return new RuleAnswerModel.ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, null, 0),
                List.of(evidence));
    }

    private RuleAnswerModel.ModelDraft draft(List<UUID> citationIds) {
        return new RuleAnswerModel.ModelDraft(
                "Follow the cited procedure.",
                "Use the cited rule for the stated situation.",
                citationIds,
                List.of(),
                "HIGH");
    }
}
