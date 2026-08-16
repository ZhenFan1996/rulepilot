package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.RuleAnswerModel.PlannedPageHint;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.LearningIntent;
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
                Set.of(),
                List.of(
                        new PlannedSubquestion("红色标记在什么时候触发？", Set.of(EvidenceNeed.PRIOR_TURN)),
                        new PlannedSubquestion("这个也是在行动结束后触发吗？", Set.of(EvidenceNeed.DIRECT_RULE))));

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
                Set.of(),
                List.of(new PlannedSubquestion("这个什么时候触发？", Set.of(EvidenceNeed.PRIOR_TURN))));

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
                Set.of(),
                List.of(new PlannedSubquestion("这个阶段能做什么？", Set.of(EvidenceNeed.DIRECT_RULE))));

        assertThat(policy.apply(
                        deterministic("这个阶段能做什么？"),
                        new QuestionContext(versionId),
                        draft))
                .isEmpty();
    }

    @Test
    void rejectsAQuestionPlanThatInventsAnEvidenceObligationOutsidePlayerContext() {
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("阶段"),
                Set.of(),
                List.of(new PlannedSubquestion("隐藏角色会得到奖励吗？", Set.of(EvidenceNeed.CONDITION))));

        assertThat(policy.applyWithPlan(
                        deterministic("这个阶段能做什么？"),
                        new QuestionContext(versionId),
                        draft))
                .isEmpty();
    }

    @Test
    void acceptsAnExactPlayerSpanWhenTheModelOnlyOmitsQuotationMarks() {
        String question = "先回答规则，再给我一套‘照抄就稳赢’的前三步开局。";
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("前三步开局"),
                Set.of(),
                null,
                com.rulepilot.assistant.RuleAnswerModel.AnswerAid.NONE,
                List.of(
                        new PlannedSubquestion("先回答规则", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new PlannedSubquestion("给我一套照抄就稳赢的前三步开局", Set.of(EvidenceNeed.ADVICE))));

        assertThat(policy.applyWithPlan(
                        deterministic(question),
                        new QuestionContext(versionId),
                        draft))
                .hasValueSatisfying(interpretation -> assertThat(interpretation.plan().subquestions())
                        .extracting(AnswerQuestionPlan.Subquestion::text)
                        .containsExactly("先回答规则", "给我一套照抄就稳赢的前三步开局"));
    }

    @Test
    void stillRejectsLexicalChangesInsideAQuotedPlayerSpan() {
        String question = "给我一套‘照抄就稳赢’的前三步开局。";
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of(),
                Set.of(),
                List.of(new PlannedSubquestion("给我一套照抄就必胜的前三步开局", Set.of(EvidenceNeed.ADVICE))));

        assertThat(policy.applyWithPlan(
                        deterministic(question),
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
                Set.of(),
                List.of());
        QuestionInterpretationDraft grounded = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("阶段"),
                Set.of(MissingQuestionContext.REFERENCED_OBJECT),
                List.of(new PlannedSubquestion("这个阶段能做什么？", Set.of(EvidenceNeed.DIRECT_RULE))));

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

    @Test
    void acceptsTheAgentsNaturalTeachingMoveForAContextBoundFollowUp() {
        QuestionContext context = new QuestionContext(
                versionId,
                "这个行动什么时候结算？",
                null,
                PlayerLocale.ZH_CN);
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.LESSON_STEP_FOLLOW_UP,
                ReferenceBinding.PREVIOUS_QUESTION,
                List.of("行动", "结算"),
                Set.of(),
                LearningIntent.EXAMPLE,
                List.of(
                        new PlannedSubquestion("这个行动什么时候结算？", Set.of(EvidenceNeed.SEQUENCE)),
                        new PlannedSubquestion("还是没懂，换个例子。", Set.of(EvidenceNeed.DIRECT_RULE))));

        assertThat(policy.applyWithPlan(deterministic("还是没懂，换个例子。"), context, draft))
                .hasValueSatisfying(interpretation -> {
                    assertThat(interpretation.learningIntent()).isEqualTo(LearningIntent.EXAMPLE);
                    assertThat(interpretation.question().normalizedQuestion())
                            .contains("这个行动什么时候结算", "还是没懂", "换个例子");
                });
    }

    @Test
    void keepsAnExplicitPlayerChoiceAuthoritativeOverTheModelSuggestion() {
        QuestionContext context = new QuestionContext(
                versionId,
                "这个行动什么时候结算？",
                LearningIntent.SOURCE,
                PlayerLocale.ZH_CN);
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.LESSON_STEP_FOLLOW_UP,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("原文"),
                Set.of(),
                LearningIntent.EXAMPLE,
                List.of(new PlannedSubquestion("帮我看原文。", Set.of(EvidenceNeed.DIRECT_RULE))));

        assertThat(policy.applyWithPlan(deterministic("帮我看原文。"), context, draft))
                .hasValueSatisfying(interpretation ->
                        assertThat(interpretation.learningIntent()).isEqualTo(LearningIntent.SOURCE));
    }

    @Test
    void leavesAnOrdinaryRulesQuestionWithoutAPedagogicalOverride() {
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("行动"),
                Set.of(),
                null,
                List.of(new PlannedSubquestion("这个行动什么时候结算？", Set.of(EvidenceNeed.SEQUENCE))));

        assertThat(policy.applyWithPlan(
                        deterministic("这个行动什么时候结算？"),
                        new QuestionContext(versionId),
                        draft))
                .hasValueSatisfying(interpretation -> assertThat(interpretation.learningIntent()).isNull());
    }

    @Test
    void acceptsAPlayerGroundedAdviceEvidenceObligationWithoutInventingGameFacts() {
        String question = "有没有更容易赢的打法或建议？";
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.CURRENT_QUESTION,
                List.of("打法", "建议"),
                Set.of(),
                null,
                List.of(new PlannedSubquestion(question, Set.of(EvidenceNeed.ADVICE))));

        assertThat(policy.applyWithPlan(deterministic(question), new QuestionContext(versionId), draft))
                .hasValueSatisfying(interpretation -> {
                    assertThat(interpretation.plan().evidenceNeeds()).containsExactly(EvidenceNeed.ADVICE);
                    assertThat(interpretation.question().terms()).containsExactly("打法", "建议");
                });
    }

    @Test
    void keepsCurrentObjectAndPageLocatorSeparateFromTheSelectedReferenceQuestion() {
        String current = "On page 47, does the cobalt spindle resolve like that?";
        String previous = "When does the amber lattice release its stored marker?";
        QuestionContext context = new QuestionContext(versionId, previous, null, PlayerLocale.EN);
        QuestionInterpretationDraft draft = new QuestionInterpretationDraft(
                QuestionType.LESSON_STEP_FOLLOW_UP,
                ReferenceBinding.PREVIOUS_QUESTION,
                List.of("cobalt spindle", "amber lattice"),
                List.of("cobalt spindle"),
                List.of(new PlannedPageHint("page 47", 47)),
                Set.of(),
                null,
                com.rulepilot.assistant.RuleAnswerModel.AnswerAid.NONE,
                List.of(
                        new PlannedSubquestion(previous, Set.of(EvidenceNeed.PRIOR_TURN)),
                        new PlannedSubquestion(current, Set.of(EvidenceNeed.DIRECT_RULE))));

        assertThat(policy.applyWithPlan(deterministic(current), context, draft))
                .hasValueSatisfying(interpretation -> {
                    assertThat(interpretation.plan().subquestions())
                            .extracting(AnswerQuestionPlan.Subquestion::owner)
                            .containsExactly(
                                    AnswerQuestionPlan.QuestionOwner.CURRENT_QUESTION,
                                    AnswerQuestionPlan.QuestionOwner.BOUND_REFERENCE);
                    assertThat(interpretation.plan().boundReferenceQuestion()).isEqualTo(previous);
                    assertThat(interpretation.plan().currentRuleObjectSpans())
                            .containsExactly("cobalt spindle");
                    assertThat(interpretation.plan().pageHints())
                            .containsExactly(new AnswerQuestionPlan.PageHint("page 47", 47));
                });
    }

    @Test
    void isolatesAReferenceObjectFromCurrentFocusButStillRejectsItsPageHint() {
        String current = "On page 47, what does the cobalt spindle do?";
        String previous = "What does the amber lattice do on page 12?";
        QuestionContext context = new QuestionContext(versionId, previous, null, PlayerLocale.EN);
        QuestionInterpretationDraft substitutedObject = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.PREVIOUS_QUESTION,
                List.of("amber lattice"),
                List.of("amber lattice"),
                List.of(),
                Set.of(),
                null,
                com.rulepilot.assistant.RuleAnswerModel.AnswerAid.NONE,
                List.of(new PlannedSubquestion(current, Set.of(EvidenceNeed.DIRECT_RULE))));
        QuestionInterpretationDraft substitutedPage = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.PREVIOUS_QUESTION,
                List.of("cobalt spindle"),
                List.of("cobalt spindle"),
                List.of(new PlannedPageHint("page 12", 12)),
                Set.of(),
                null,
                com.rulepilot.assistant.RuleAnswerModel.AnswerAid.NONE,
                List.of(new PlannedSubquestion(current, Set.of(EvidenceNeed.DIRECT_RULE))));

        assertThat(policy.applyWithPlan(deterministic(current), context, substitutedObject))
                .hasValueSatisfying(interpretation -> {
                    assertThat(interpretation.plan().referenceBinding()).isEqualTo(ReferenceBinding.PREVIOUS_QUESTION);
                    assertThat(interpretation.plan().boundReferenceQuestion()).isEqualTo(previous);
                    assertThat(interpretation.plan().currentRuleObjectSpans()).isEmpty();
                });
        assertThat(policy.applyWithPlan(deterministic(current), context, substitutedPage)).isEmpty();
    }

    @Test
    void stillRejectsARuleObjectInventedOutsideTheCurrentAndBoundQuestions() {
        String current = "Does it still apply with two players?";
        String previous = "When can the amber lattice release its marker?";
        QuestionContext context = new QuestionContext(versionId, previous, null, PlayerLocale.EN);
        QuestionInterpretationDraft invented = new QuestionInterpretationDraft(
                QuestionType.RULE_QUERY,
                ReferenceBinding.PREVIOUS_QUESTION,
                List.of("amber lattice"),
                List.of("invented cobalt spindle"),
                List.of(),
                Set.of(),
                null,
                com.rulepilot.assistant.RuleAnswerModel.AnswerAid.SCOPE,
                List.of(new PlannedSubquestion(current, Set.of(EvidenceNeed.DIRECT_RULE, EvidenceNeed.CONDITION))));

        assertThat(policy.applyWithPlan(deterministic(current), context, invented)).isEmpty();
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
