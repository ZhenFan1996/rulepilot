package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.application.AnswerRetrievalPlanner.RetrievalPurpose;
import org.junit.jupiter.api.Test;

class AnswerRetrievalProcedureIntentsTest {

    @Test
    void keepsFocusedProcedureQueriesInTheEstablishedPriorityOrder() {
        var intents = AnswerRetrievalProcedureIntents.plan(
                "At game end, how is the winner scored if the draw deck is empty after I end turn and draw an event card?");

        assertThat(intents).extracting(intent -> intent.purpose())
                .containsExactly(
                        RetrievalPurpose.ENDGAME_RESOLUTION,
                        RetrievalPurpose.EXHAUSTED_SOURCE,
                        RetrievalPurpose.END_TURN_PROCEDURE);
        assertThat(intents.getFirst().query()).contains("end condition", "final scoring");
        assertThat(intents.get(1).query()).contains("depleted", "reshuffle");
        assertThat(intents.get(2).query()).contains("completed turn", "resolve");
    }

    @Test
    void doesNotTreatAnOrdinaryCardQuestionAsAProcedureRecoveryCase() {
        assertThat(AnswerRetrievalProcedureIntents.plan("Can I score this card now?")).isEmpty();
    }

    @Test
    void expandsAPlayersPlainLanguageSameNumberQuestionIntoAResolutionLookup() {
        var intents = AnswerRetrievalProcedureIntents.plan(
                "What happens when two players play the same number?");

        assertThat(intents).singleElement().satisfies(intent -> {
            assertThat(intent.purpose()).isEqualTo(RetrievalPurpose.MATCHING_VALUE_RESOLUTION);
            assertThat(intent.query()).contains("same number", "collision", "bump", "priority", "resolution");
        });
    }
}
