package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRetrievalPlannerTest {

    @Test
    void buildsPrimaryAndContextualSupplementaryIntents() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Can I play this card now?",
                "can i play this card now?",
                QuestionType.SITUATION_QUERY,
                List.of("play", "card"),
                Set.of(),
                "ACTIONS");
        var context = new QuestionContext(versionId, "ACTIONS", "ACTION_PHASE", 4, Set.of());

        var intents = AnswerRetrievalPlanner.plan(question, context);

        assertThat(intents).hasSize(2);
        assertThat(intents.getFirst().query()).isEqualTo(question.normalizedQuestion());
        assertThat(intents.getFirst().sectionTypes()).isEmpty();
        assertThat(intents.get(1).query())
                .contains("legal action", "ACTION PHASE", "4 players", "4人");
        assertThat(intents.get(1).sectionTypes()).contains("ACTIONS");
        assertThat(intents.get(1).currentSectionType()).isEqualTo("ACTIONS");
    }

    @Test
    void infersScoringScopeWithoutTrustingUnknownSectionNames() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "How are points scored?",
                "how are points scored?",
                QuestionType.RULE_QUERY,
                List.of("points", "scored"),
                Set.of(),
                null);

        var intents = AnswerRetrievalPlanner.plan(
                question, new QuestionContext(versionId, "user supplied section", null, null, Set.of()));

        assertThat(intents.get(1).sectionTypes()).containsExactly("SCORING");
        assertThat(intents.get(1).currentSectionType()).isNull();
    }
}
