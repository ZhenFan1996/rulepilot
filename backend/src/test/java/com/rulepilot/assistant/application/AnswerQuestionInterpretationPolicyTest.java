package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerQuestionInterpretationPolicyTest {

    private final AnswerQuestionInterpretationPolicy policy = new AnswerQuestionInterpretationPolicy();
    private final UUID versionId = UUID.randomUUID();

    @Test
    void resolvesAVagueFollowUpThroughTheBoundedPriorGroundedTurn() {
        UnderstoodQuestion deterministic = deterministic("这个也是在行动结束后触发吗？");
        QuestionContext context = new QuestionContext(
                versionId,
                null,
                null,
                PlayerLocale.ZH_CN,
                new PriorTurnReference(
                        versionId,
                        "红色标记在什么时候触发？",
                        "它在行动结束后触发。",
                        List.of(new PriorCitationReference(UUID.randomUUID(), versionId, 4, 4))));
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.LESSON_STEP_FOLLOW_UP,
                ReferenceBinding.PRIOR_GROUNDED_TURN,
                List.of("红色标记", "行动结束后"),
                Set.of());

        assertThat(policy.apply(deterministic, context, draft)).hasValueSatisfying(understood -> {
            assertThat(understood.needsClarification()).isFalse();
            assertThat(understood.normalizedQuestion())
                    .contains("红色标记在什么时候触发", "这个也是在行动结束后触发吗");
            assertThat(understood.terms()).containsExactly("红色标记", "行动结束后");
        });
    }

    @Test
    void rejectsAContextBindingThatTheApplicationDidNotSupply() {
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.LESSON_STEP_FOLLOW_UP,
                ReferenceBinding.PRIOR_GROUNDED_TURN,
                List.of(),
                Set.of());

        assertThat(policy.apply(
                        deterministic("这个什么时候触发？"),
                        new QuestionContext(versionId),
                        draft))
                .isEmpty();
    }

    @Test
    void rejectsTermsInventedByTheModelInsteadOfCopyingPlayerWording() {
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("模型猜出的组件"),
                Set.of());

        assertThat(policy.apply(
                        deterministic("这个阶段能做什么？"),
                        new QuestionContext(versionId),
                        draft))
                .isEmpty();
    }

    @Test
    void requiresClarificationBindingAndMissingContextToAgree() {
        QuestionInterpretationDraft ambiguous = new QuestionInterpretationDraft(
                QuestionType.SITUATION_QUERY,
                ReferenceBinding.NEEDS_CLARIFICATION,
                List.of("这个"),
                Set.of());
        QuestionInterpretationDraft grounded = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("阶段"),
                Set.of(MissingQuestionContext.REFERENCED_OBJECT));

        assertThat(policy.apply(
                        deterministic("这个阶段能做什么？"),
                        new QuestionContext(versionId),
                        ambiguous))
                .isEmpty();
        assertThat(policy.apply(
                        deterministic("这个阶段能做什么？"),
                        new QuestionContext(versionId),
                        grounded))
                .isEmpty();
    }

    private UnderstoodQuestion deterministic(String question) {
        return new UnderstoodQuestion(
                versionId,
                question,
                question.toLowerCase(java.util.Locale.ROOT),
                QuestionType.SITUATION_QUERY,
                List.of(),
                Set.of(MissingQuestionContext.REFERENCED_OBJECT));
    }
}
