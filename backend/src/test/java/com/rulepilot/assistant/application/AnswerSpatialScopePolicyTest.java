package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerSpatialScopePolicyTest {

    @Test
    void requestsRepairWhenAColumnQuestionInventsExtraRows() {
        var request = request("我刚把标记移到左列旁，下一位能从哪些位置拿牌？");
        var draft = draft("不能取最左列，也不能取最上一行和最下行。", "中间行和最右列都可以取。");

        assertThat(AnswerSpatialScopePolicy.needsRepair(request, draft)).isTrue();
    }

    @Test
    void doesNotRepairWhenTheAnswerOnlyUsesThePositionNamedByThePlayer() {
        var request = request("我刚把标记移到左列旁，下一位能从哪些位置拿牌？");
        var draft = draft("不能取左列。", "其他位置要按规则书给出的标记限制确认。");

        assertThat(AnswerSpatialScopePolicy.needsRepair(request, draft)).isFalse();
    }

    @Test
    void reducesRepeatedSpatialInferenceToACitedPartialRuling() {
        var request = request("我刚把标记移到左列旁，下一位能从哪些位置拿牌？");
        var draft = draft("不能取最左列，也不能取最上一行和最下行。", "中间行和最右列都可以取。");

        var bounded = AnswerSpatialScopePolicy.boundRepeatedInference(request, draft);

        assertThat(bounded.answerBasis()).isEqualTo("GROUNDED_APPLICATION");
        assertThat(bounded.shortVerdict()).doesNotContain("最上", "最下", "最右", "中间");
        assertThat(bounded.citationIds()).containsExactlyElementsOf(draft.citationIds());
    }

    @Test
    void doesNotTreatOutrightVictoryAsTheEnglishWordRight() {
        var request = request("What happens if the sixth-round winner leads outright?");
        var draft = draft(
                "The sixth-round winner immediately wins.",
                "If that player leads every other player in victory points, the game ends.");

        assertThat(AnswerSpatialScopePolicy.needsRepair(request, draft)).isFalse();
        assertThat(AnswerSpatialScopePolicy.boundRepeatedInference(request, draft)).isSameAs(draft);
    }

    private RuleAnswerModel.ModelRequest request(String question) {
        return new RuleAnswerModel.ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, com.rulepilot.assistant.PlayerLocale.ZH_CN),
                List.of(new RuleAnswerModel.EvidenceInput(UUID.randomUUID(), "ACTIONS", "Actions", "Take a row.", 1, 1)));
    }

    private RuleAnswerModel.ModelDraft draft(String verdict, String explanation) {
        return new RuleAnswerModel.ModelDraft(
                verdict, explanation, List.of(UUID.randomUUID()), List.of(), "HIGH");
    }
}
