package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerCacheScopePolicyTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void scopesAFollowUpByPolicyLanguageAndLearningIntentOnly() {
        var context = new QuestionContext(
                documentVersionId,
                "回合流程",
                "行动阶段",
                3,
                Set.of(UUID.randomUUID()),
                " 上一问是什么？ ",
                LearningIntent.EXAMPLE,
                PlayerLocale.EN);

        var key = AnswerCacheScopePolicy.key(
                "answer-v99",
                7,
                question("What happens after that?"),
                context);

        assertThat(key).satisfies(scoped -> {
            assertThat(scoped.documentVersionId()).isEqualTo(documentVersionId);
            assertThat(scoped.ruleDataVersion()).isEqualTo(7);
            assertThat(scoped.normalizedQuestion())
                    .isEqualTo("EXAMPLE:answer-v99:EN:上一问是什么？ -> What happens after that?");
            assertThat(scoped.currentLessonSection()).isEqualTo("回合流程");
            assertThat(scoped.outputLanguage()).isEqualTo(PlayerLocale.EN);
        });
    }

    @Test
    void keepsAnUnthreadedRuleQuestionFreeOfLiveAndLearningPrefixes() {
        var key = AnswerCacheScopePolicy.key(
                "answer-v99",
                1,
                question("How many cards can I draw?"),
                new QuestionContext(documentVersionId, null));

        assertThat(key.normalizedQuestion()).isEqualTo("answer-v99:ZH_CN:How many cards can I draw?");
    }

    @Test
    void legacyTableStateCannotPartitionTheSameRuleAnswer() {
        var question = question("How many cards can I draw?");
        var first = AnswerCacheScopePolicy.key(
                "answer-v99",
                3,
                question,
                new QuestionContext(documentVersionId, "ACTIONS", "FIRST_PHASE", 2, Set.of(UUID.randomUUID())));
        var second = AnswerCacheScopePolicy.key(
                "answer-v99",
                3,
                question,
                new QuestionContext(documentVersionId, "ACTIONS", "FINAL_PHASE", 8, Set.of(UUID.randomUUID())));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsAnUnversionedAnswerPolicyInsteadOfSilentlySharingOldAnswers() {
        assertThatThrownBy(() -> AnswerCacheScopePolicy.key(
                        " ",
                        1,
                        question("How many cards can I draw?"),
                        new QuestionContext(documentVersionId, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("answer cache policy version is required");
    }

    private UnderstoodQuestion question(String normalizedQuestion) {
        return new UnderstoodQuestion(
                documentVersionId,
                normalizedQuestion,
                normalizedQuestion,
                QuestionType.RULE_QUERY,
                List.of(),
                Set.of(),
                null);
    }
}
