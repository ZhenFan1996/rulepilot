package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerStructuredAidPolicyTest {

    private final UUID cited = UUID.randomUUID();
    private final UUID availableButUncited = UUID.randomUUID();

    @Test
    void routesOnlyByTheAcceptedStructuredAidAndNeverByQuestionVocabulary() {
        ModelRequest arbitraryQuestion = request("这句话故意不含任何产品关键词。", AnswerAid.TIMING);
        ModelRequest misleadingQuestion = request("What are the exceptions and tie breakers?", AnswerAid.NONE);

        assertThat(AnswerStructuredAidPolicy.required(arbitraryQuestion, AnswerAid.TIMING)).isTrue();
        assertThat(AnswerStructuredAidPolicy.required(arbitraryQuestion, AnswerAid.EXCEPTIONS)).isFalse();
        assertThat(AnswerStructuredAidPolicy.required(misleadingQuestion, AnswerAid.EXCEPTIONS)).isFalse();
    }

    @Test
    void permitsAnOmittedSelectedPresentationAidButRejectsAnUnselectedPayload() {
        ModelRequest timing = request("任意问题", AnswerAid.TIMING);

        AnswerStructuredAidPolicy.validateSelection(timing, AnswerAid.TIMING, true, "timing resolutions");
        assertThatThrownBy(() -> AnswerStructuredAidPolicy.validateSelection(
                        request("任意问题", AnswerAid.CALCULATION),
                        AnswerAid.CALCULATION,
                        true,
                        "calculations"))
                .hasMessageContaining("required");
        assertThatThrownBy(() -> AnswerStructuredAidPolicy.validateSelection(
                        timing, AnswerAid.TIE, false, "tie resolutions"))
                .hasMessageContaining("not selected");

        AnswerStructuredAidPolicy.validateSelection(timing, AnswerAid.TIMING, false, "timing resolutions");
        AnswerStructuredAidPolicy.validateSelection(timing, AnswerAid.TIE, true, "tie resolutions");
    }

    @Test
    void limitsStructuredAidCitationsToBothRetrievedAndPublishedAnswerEvidence() {
        ModelRequest request = request("任意问题", AnswerAid.SOURCE);
        ModelDraft draft = new ModelDraft(
                true, null, "结论", "解释", List.of(cited), List.of(), "HIGH", "DIRECT_RULE");

        assertThat(AnswerStructuredAidPolicy.citations(request, draft, List.of(cited), "source"))
                .containsExactly(cited);
        assertThatThrownBy(() -> AnswerStructuredAidPolicy.citations(
                        request, draft, List.of(availableButUncited), "source"))
                .hasMessageContaining("outside the answer scope");
        assertThatThrownBy(() -> AnswerStructuredAidPolicy.citations(
                        request, draft, List.of(UUID.randomUUID()), "source"))
                .hasMessageContaining("outside the answer scope");
    }

    private ModelRequest request(String question, AnswerAid answerAid) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(
                        new EvidenceInput(cited, "RULE", "Rule", "Direct rule text.", 2, 2),
                        new EvidenceInput(
                                availableButUncited, "RULE", "Other", "Another direct rule.", 3, 3)),
                Set.of(EvidenceNeed.DIRECT_RULE),
                answerAid);
    }
}
