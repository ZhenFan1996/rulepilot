package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.AnswerRetrievalContext.LearningIntent;
import com.rulepilot.retrieval.AnswerRetrievalPlan.EvidenceNeed;
import com.rulepilot.retrieval.AnswerRetrievalQuestion.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRetrievalPlannerTest {

    private final UUID versionId = UUID.randomUUID();

    @Test
    void followsValidatedSubquestionsAndEvidenceNeedsWithoutPunctuationHeuristics() {
        AnswerRetrievalQuestion question = question("行动后先补牌吗，还有例外情况吗？", List.of("补牌", "例外"));
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(
                        new AnswerRetrievalPlan.Subquestion("行动后先补牌吗", Set.of(EvidenceNeed.SEQUENCE)),
                        new AnswerRetrievalPlan.Subquestion("例外情况吗", Set.of(EvidenceNeed.EXCEPTION))),
                false);

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
        AnswerRetrievalQuestion question = question("有没有更容易赢的打法或建议？", List.of("打法", "建议"));
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        question.normalizedQuestion(), Set.of(EvidenceNeed.ADVICE))),
                false);

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
        AnswerRetrievalQuestion question = question("When does the phase end?", List.of());
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(new AnswerRetrievalPlan.Subquestion(
                        "When does the phase end?", Set.of(EvidenceNeed.DIRECT_RULE))),
                false);

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
        AnswerRetrievalQuestion question = question("Can this action happen now?", List.of());

        var intents = AnswerRetrievalPlanner.plan(question, context());

        assertThat(intents.getFirst().query()).isEqualTo("Can this action happen now? direct rule clause");
        assertThat(intents).extracting(AnswerRetrievalPlanner.RetrievalIntent::query)
                .anySatisfy(query -> assertThat(query).contains("rule condition consequence"))
                .allSatisfy(query -> assertThat(query)
                        .doesNotContain("permission", "prohibition", "player count", "scoring"));
    }

    @Test
    void supplementaryQueryUsesCallerContextTermsAndLearningIntentAsRetrievalData() {
        AnswerRetrievalQuestion question = question("请解释上一条里的术语。", List.of("声望里程碑"));
        AnswerRetrievalContext context = new AnswerRetrievalContext(
                versionId, "声望轨道上的里程碑是什么意思？", LearningIntent.DEFINE);

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
        AnswerRetrievalPlan plan = new AnswerRetrievalPlan(
                List.of(
                        subquestion("one"),
                        subquestion("two"),
                        subquestion("three"),
                        subquestion("four")),
                false);
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

    private AnswerRetrievalPlan.Subquestion subquestion(String text) {
        return new AnswerRetrievalPlan.Subquestion(text, Set.of(EvidenceNeed.DIRECT_RULE));
    }

    private AnswerRetrievalQuestion question(String text, List<String> terms) {
        return new AnswerRetrievalQuestion(text, QuestionType.RULE_QUERY, terms);
    }

    private AnswerRetrievalContext context() {
        return new AnswerRetrievalContext(versionId);
    }
}
