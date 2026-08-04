package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.domain.RuleExceptionClause;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerShortVerdictPolicyTest {

    private final List<RuleExceptionClause> clauses = List.of(new RuleExceptionClause(
            "When a cited condition applies", "Use its cited effect", List.of(UUID.randomUUID())));

    @Test
    void keepsAnAlreadyBoundedVerdictUnchanged() {
        String verdict = "Apply the matching exception.";

        assertThat(AnswerShortVerdictPolicy.normalizeCitedSummary(verdict, clauses))
                .isEqualTo(verdict);
    }

    @Test
    void keepsACompleteFirstSentenceForAnOverlongGeneralVerdict() {
        String verdict = "The game ends after the final round. "
                + "The complete cited scoring sequence and all tiebreakers follow. ".repeat(6);

        assertThat(AnswerShortVerdictPolicy.normalizeCitedSummary(verdict, List.of()))
                .isEqualTo("The game ends after the final round.");
    }

    @Test
    void preservesACompleteFirstSentenceAndPointsToTheValidatedList() {
        String verdict = "The first matching condition changes the normal result. "
                + "A second condition has a separately cited effect that needs a much longer explanation. ".repeat(4);

        String normalized = AnswerShortVerdictPolicy.normalizeCitedSummary(verdict, clauses);

        assertThat(normalized)
                .startsWith("The first matching condition changes the normal result.")
                .endsWith("See the cited exception list below for every condition and effect.")
                .hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void usesASafeChineseFallbackWhenEvenTheFirstSentenceIsTooLong() {
        String verdict = "这个超长句子仍然没有结束".repeat(30) + "。";

        assertThat(AnswerShortVerdictPolicy.normalizeCitedSummary(verdict, clauses))
                .isEqualTo("请按下方逐条引用的例外清单，应用所有符合的条件及其对应后果。");
    }

    @Test
    void usesANonMechanicalPointerWhenAGeneralVerdictHasNoCompleteBoundedSentence() {
        String verdict = "This overlong ruling never reaches a safe sentence boundary ".repeat(8);

        assertThat(AnswerShortVerdictPolicy.normalizeCitedSummary(verdict, List.of()))
                .isEqualTo("See the cited explanation below for the complete ruling.");
    }
}
