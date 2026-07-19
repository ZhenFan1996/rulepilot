package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicQuestionUnderstandingTest {

    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();
    private final UUID versionId = UUID.randomUUID();

    @Test
    void usesLessonContextForStepFollowUp() {
        var result = understanding.understand(
                "Why do we place the board here?",
                new QuestionContext(versionId, "SETUP", null, 4, Set.of()));

        assertThat(result.type()).isEqualTo(QuestionType.LESSON_STEP_FOLLOW_UP);
        assertThat(result.currentLessonSection()).isEqualTo("SETUP");
        assertThat(result.terms()).contains("place", "board", "here");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void identifiesRuleQueryAndExpansionGap() {
        var result = understanding.understand(
                "How are victory points scored with the expansion?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(result.type()).isEqualTo(QuestionType.RULE_QUERY);
        assertThat(result.terms()).contains("victory", "points", "scored", "expansion");
        assertThat(result.missingContext()).containsExactly(MissingQuestionContext.EXPANSION_SELECTION);
    }

    @Test
    void identifiesSituationAndMissingPhase() {
        var result = understanding.understand(
                "Can I play this card from my hand?",
                new QuestionContext(versionId, null, null, 3, Set.of()));

        assertThat(result.type()).isEqualTo(QuestionType.SITUATION_QUERY);
        assertThat(result.terms()).contains("play", "card", "hand");
        assertThat(result.missingContext())
                .containsExactlyInAnyOrder(
                        MissingQuestionContext.GAME_PHASE, MissingQuestionContext.SITUATION_DETAILS);
    }

    @Test
    void asksForLessonSectionWhenStepReferenceHasNoContext() {
        var result = understanding.understand(
                "Why did we do that in the previous step?",
                new QuestionContext(versionId, null, null, null, Set.of()));

        assertThat(result.type()).isEqualTo(QuestionType.LESSON_STEP_FOLLOW_UP);
        assertThat(result.missingContext()).containsExactly(MissingQuestionContext.CURRENT_LESSON_SECTION);
    }

    @Test
    void asksForSituationDetailsInsteadOfGuessingAChineseFollowUpReference() {
        var result = understanding.understand(
                "那我还能再做一次吗？",
                new QuestionContext(versionId, null, "ACTION_PHASE", 4, Set.of()));

        assertThat(result.type()).isEqualTo(QuestionType.SITUATION_QUERY);
        assertThat(result.missingContext()).containsExactly(MissingQuestionContext.SITUATION_DETAILS);
    }
}
