package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import org.junit.jupiter.api.Test;

class AnswerRetrievalProcedureIntentsTest {

    @Test
    void keepsEndgameAsTheProductLevelIntentInsteadOfAddingCorpusLocalProcedureBranches() {
        var intents = AnswerRetrievalProcedureIntents.plan(
                "At game end, how is the winner scored if the draw deck is empty after I end turn and draw an event card?");

        assertThat(intents).extracting(intent -> intent.purpose())
                .containsExactly(RetrievalPurpose.ENDGAME_RESOLUTION);
        assertThat(intents.getFirst().query()).contains("end condition", "final scoring");
    }

    @Test
    void doesNotTreatAnOrdinaryCardQuestionAsAProcedureRecoveryCase() {
        assertThat(AnswerRetrievalProcedureIntents.plan("Can I score this card now?")).isEmpty();
    }

    @Test
    void expandsAnUnclassifiedConditionOnlyWithNeutralProcedureFacets() {
        assertThat(AnswerRetrievalProcedureIntents.plan(
                        "What happens when two players choose equal values?"))
                .singleElement()
                .satisfies(intent -> {
                    assertThat(intent.purpose()).isEqualTo(RetrievalPurpose.CONDITION_PROCEDURE);
                    assertThat(intent.query())
                            .contains("equal values", "condition", "procedure", "consequence")
                            .doesNotContain("collision", "bump", "priority");
                });
    }

    @Test
    void expandsAnEndTriggerFollowUpIntoTheSameFocusedEndgameLookupAsScoring() {
        var intents = AnswerRetrievalProcedureIntents.plan(
                "如果我建满两行但有禁用地点，可以选择结束游戏吗？其他玩家还会继续玩吗？");

        assertThat(intents).singleElement().satisfies(intent -> {
            assertThat(intent.purpose()).isEqualTo(RetrievalPurpose.ENDGAME_RESOLUTION);
            assertThat(intent.query()).contains("finish the round", "equal turns", "相同回合数", "其他玩家继续");
        });
    }
}
