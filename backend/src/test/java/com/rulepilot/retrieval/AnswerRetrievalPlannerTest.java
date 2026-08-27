package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalQuestion.QuestionType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerRetrievalPlannerTest {

    @Test
    void usesValidatedSubquestionsWithoutTranslatingEvidenceEnumsIntoKeywords() {
        AnswerRetrievalQuestion question = question("行动后先补牌吗，还有例外情况吗？", List.of("补牌", "例外"));
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(
                        new AnswerRetrievalPlan.Subquestion("行动后先补牌吗", Set.of(EvidenceNeed.SEQUENCE)),
                        new AnswerRetrievalPlan.Subquestion("例外情况吗", Set.of(EvidenceNeed.EXCEPTION))),
                false);

        var intents = AnswerRetrievalPlanner.plan(question, plan);

        assertThat(intents.getFirst().query()).startsWith(question.currentQuestion());
        assertThat(intents).filteredOn(AnswerRetrievalPlanner.RetrievalIntent::directQuestion)
                .extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .contains(
                        "行动后先补牌吗",
                        "例外情况吗")
                .allSatisfy(query -> assertThat(query)
                        .doesNotContain("order timing procedure", "exception restriction"));
    }

    @Test
    void doesNotInjectApplicationOwnedAdviceKeywordsIntoRetrievalQueries() {
        AnswerRetrievalQuestion question = question("有没有更容易赢的打法或建议？", List.of("打法", "建议"));
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        question.normalizedQuestion(), Set.of(EvidenceNeed.ADVICE))),
                false);

        var queries = AnswerRetrievalPlanner.plan(question, plan).stream()
                .map(AnswerRetrievalPlanner.RetrievalIntent::query)
                .toList();

        assertThat(queries)
                .containsExactly("有没有更容易赢的打法或建议？")
                .allSatisfy(query -> assertThat(query).doesNotContain(
                        "source-authored recommendation",
                        "preferred choice",
                        "caution avoid"));
    }

    @Test
    void usesOnlyBoundedRetrievalQueriesOwnedByTheirStructuredSubquestion() {
        AnswerRetrievalQuestion question = question("When does the phase end?", List.of());
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        "When does the phase end?",
                        Set.of(EvidenceNeed.DIRECT_RULE),
                        AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION,
                        List.of("phase end cleanup", "PHASE END CLEANUP"))),
                false);

        var intents = AnswerRetrievalPlanner.plan(question, plan);

        assertThat(intents.getFirst().directQuestion()).isTrue();
        assertThat(intents.getFirst().query()).isEqualTo("When does the phase end?");
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .contains("phase end cleanup", "PHASE END CLEANUP");
    }

    @Test
    void fallbackPlanDoesNotInventRetrievalVocabulary() {
        AnswerRetrievalQuestion question = question("Can this action happen now?", List.of());

        var intents = AnswerRetrievalPlanner.plan(question);

        assertThat(intents.getFirst().query()).isEqualTo("Can this action happen now?");
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .allSatisfy(query -> assertThat(query)
                        .doesNotContain(
                                "direct rule clause",
                                "rule condition consequence",
                                "permission",
                                "prohibition",
                                "player count",
                                "scoring"));
    }

    @Test
    void keepsCurrentAndBoundReferenceQuestionsAsSeparateStructuredQueries() {
        AnswerRetrievalQuestion question = question("请解释上一条里的术语。", List.of("声望里程碑"));
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(
                        new AnswerRetrievalPlan.Subquestion(
                                "请解释上一条里的术语。",
                                Set.of(EvidenceNeed.DEFINITION),
                                AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION),
                        new AnswerRetrievalPlan.Subquestion(
                                "声望轨道上的里程碑是什么意思？",
                                Set.of(EvidenceNeed.PRIOR_TURN),
                                AnswerRetrievalPlan.QuestionOwner.BOUND_REFERENCE)),
                false,
                AnswerRetrievalPlan.ReferenceBinding.PREVIOUS_QUESTION,
                "声望轨道上的里程碑是什么意思？",
                List.of(),
                List.of());

        var queries = AnswerRetrievalPlanner.plan(question, plan).stream()
                .map(AnswerRetrievalPlanner.RetrievalIntent::query)
                .toList();

        assertThat(queries).contains(
                "请解释上一条里的术语。",
                "声望轨道上的里程碑是什么意思？");
        assertThat(queries).doesNotContain("声望里程碑");
        assertThat(queries).allSatisfy(query -> assertThat(query).doesNotContain("definition terminology"));
    }

    @Test
    void usesRuleObjectsAsTheirOwnTypedQueriesInsteadOfConcatenatingOrParsingTheQuestion() {
        AnswerRetrievalQuestion question = question("Compare A - 01 with the ordinary action.", List.of());
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        "Compare both rule objects.", Set.of(EvidenceNeed.RELATIONSHIP))),
                false,
                AnswerRetrievalPlan.ReferenceBinding.CURRENT_QUESTION,
                null,
                List.of("A - 01", "ordinary action"),
                List.of());

        var queries = AnswerRetrievalPlanner.plan(question, plan).stream()
                .map(AnswerRetrievalPlanner.RetrievalIntent::query)
                .toList();

        assertThat(queries).contains(
                "Compare A - 01 with the ordinary action.",
                "Compare both rule objects.",
                "A - 01",
                "ordinary action");
        assertThat(queries).doesNotContain("A-01");
    }

    @Test
    void enforcesIntentCountAndQueryLengthBudgets() {
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(
                        subquestion("one"),
                        subquestion("two"),
                        subquestion("three"),
                        subquestion("four")),
                false);
        var intents = AnswerRetrievalPlanner.plan(question("question", List.of()), plan);

        assertThat(intents).hasSize(5);
        assertThat(intents).allSatisfy(intent -> assertThat(intent.query().length()).isLessThanOrEqualTo(500));
    }

    @Test
    void rejectsAnOversizedStructuredRetrievalQueryInsteadOfSilentlyTruncatingIt() {
        String oversized = "x".repeat(700);

        assertThatThrownBy(() -> new AnswerRetrievalPlan.Subquestion(
                        "question",
                        Set.of(EvidenceNeed.DIRECT_RULE),
                        AnswerRetrievalPlan.QuestionOwner.CURRENT_QUESTION,
                        List.of(oversized)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesPlannerInputsAndIntentShape() {
        assertThatThrownBy(() -> AnswerRetrievalPlanner.plan(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerRetrievalPlanner.RetrievalIntent(
                        " ", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AnswerRetrievalPlan.Subquestion subquestion(String text) {
        return new AnswerRetrievalPlan.Subquestion(text, Set.of(EvidenceNeed.DIRECT_RULE));
    }

    private AnswerRetrievalQuestion question(String text, List<String> terms) {
        return new AnswerRetrievalQuestion(text, QuestionType.RULE_QUERY, terms);
    }
}
