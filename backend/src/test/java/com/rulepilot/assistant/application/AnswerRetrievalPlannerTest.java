package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRetrievalPlannerTest {

    private final UUID versionId = UUID.randomUUID();

    @Test
    void followsValidatedSubquestionsAndEvidenceNeedsWithoutPunctuationHeuristics() {
        UnderstoodQuestion question = question("行动后先补牌吗，还有例外情况吗？", List.of("补牌", "例外"));
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(
                        new AnswerQuestionPlan.Subquestion("行动后先补牌吗", Set.of(EvidenceNeed.SEQUENCE)),
                        new AnswerQuestionPlan.Subquestion("例外情况吗", Set.of(EvidenceNeed.EXCEPTION))),
                true,
                AnswerAid.WALKTHROUGH,
                ReferenceBinding.CURRENT_QUESTION);

        var intents = AnswerRetrievalPlanner.plan(question, context(), List.of(), plan);

        assertThat(intents).filteredOn(AnswerRetrievalPlanner.RetrievalIntent::directQuestion)
                .extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .containsExactly(
                        "行动后先补牌吗 order timing procedure",
                        "例外情况吗 exception restriction");
        assertThat(intents).allSatisfy(intent -> {
            assertThat(intent.sectionTypes()).isEmpty();
            assertThat(intent.currentSectionType()).isNull();
            assertThat(intent.purpose()).isEqualTo(AnswerRetrievalPlanner.RetrievalPurpose.GENERAL);
        });
    }

    @Test
    void addsSourceAuthoredAdviceCuesOnlyWhenThePlanRequestsAdviceEvidence() {
        UnderstoodQuestion question = question("有没有更容易赢的打法或建议？", List.of("打法", "建议"));
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        question.normalizedQuestion(), Set.of(EvidenceNeed.ADVICE))),
                true);

        var queries = AnswerRetrievalPlanner.plan(question, context(), List.of(), plan).stream()
                .map(AnswerRetrievalPlanner.RetrievalIntent::query)
                .toList();

        assertThat(queries)
                .contains("有没有更容易赢的打法或建议？ source-authored recommendation caution preferred choice")
                .anySatisfy(query -> assertThat(query).contains("preferred choice ideal should recommendation advice"))
                .anySatisfy(query -> assertThat(query).contains("caution avoid warning watch out"));
    }

    @Test
    void keepsBoundedModelRewritesAfterDirectSubquestionsAndDeduplicatesQueries() {
        UnderstoodQuestion question = question("When does the phase end?", List.of());
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        "When does the phase end?", Set.of(EvidenceNeed.DIRECT_RULE))),
                true);

        var intents = AnswerRetrievalPlanner.plan(
                question,
                context(),
                List.of("phase end cleanup", " PHASE END CLEANUP ", " "),
                plan);

        assertThat(intents.getFirst().directQuestion()).isTrue();
        assertThat(intents.getFirst().query()).isEqualTo("When does the phase end? direct rule clause");
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .containsOnlyOnce("phase end cleanup");
    }

    @Test
    void fallbackPlanAddsOnlyGenericSyntacticFacets() {
        UnderstoodQuestion question = question("Can this action happen now?", List.of());

        var intents = AnswerRetrievalPlanner.plan(question, context());

        assertThat(intents.getFirst().query()).isEqualTo("Can this action happen now? direct rule clause");
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anySatisfy(query -> assertThat(query).contains("rule condition consequence"))
                .allSatisfy(query -> assertThat(query)
                        .doesNotContain("permission", "prohibition", "player count", "scoring"));
    }

    @Test
    void supplementaryQueryUsesCallerContextTermsAndLearningIntentAsRetrievalData() {
        UnderstoodQuestion question = question("请解释上一条里的术语。", List.of("声望里程碑"));
        QuestionContext context = new QuestionContext(
                versionId, "声望轨道上的里程碑是什么意思？", LearningIntent.DEFINE, PlayerLocale.ZH_CN);

        var queries = AnswerRetrievalPlanner.plan(question, context).stream()
                .map(AnswerRetrievalPlanner.RetrievalIntent::query)
                .toList();

        assertThat(queries).anySatisfy(query -> assertThat(query).contains(
                "请解释上一条里的术语。",
                "声望轨道上的里程碑是什么意思？",
                "声望里程碑",
                "definition terminology"));
    }

    @Test
    void enforcesIntentCountAndQueryLengthBudgets() {
        AnswerQuestionPlan plan = new AnswerQuestionPlan(
                List.of(
                        subquestion("one"),
                        subquestion("two"),
                        subquestion("three"),
                        subquestion("four")),
                true);
        String longRewrite = "x".repeat(700);

        var intents = AnswerRetrievalPlanner.plan(
                question("question", List.of()), context(), List.of(longRewrite, "six"), plan);

        assertThat(intents).hasSize(5);
        assertThat(intents.getLast().query()).hasSize(500);
    }

    @Test
    void validatesPlannerInputsAndIntentShape() {
        assertThatThrownBy(() -> AnswerRetrievalPlanner.plan(null, context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnswerRetrievalPlanner.plan(question("question", List.of()), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerRetrievalPlanner.RetrievalIntent(
                        " ", Set.of(), null, true, AnswerRetrievalPlanner.RetrievalPurpose.GENERAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AnswerQuestionPlan.Subquestion subquestion(String text) {
        return new AnswerQuestionPlan.Subquestion(text, Set.of(EvidenceNeed.DIRECT_RULE));
    }

    private UnderstoodQuestion question(String text, List<String> terms) {
        return new UnderstoodQuestion(
                versionId, text, text, QuestionType.RULE_QUERY, terms, Set.of());
    }

    private QuestionContext context() {
        return new QuestionContext(versionId);
    }
}
