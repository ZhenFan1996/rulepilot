package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.CalculationOperandSource;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleAnswerStructuredOutputContractTest {

    private final UUID citationId = UUID.randomUUID();

    @Test
    void bindsNaturalPlayerProseAndTypedCalculationDataInOneJsonEnvelope() {
        ModelDraft draft = SpringAiRuleAnswerModel.parseModelDraft(validCalculationJson());

        assertThat(draft.shortVerdict()).isEqualTo("你得到 10 分。\n现在可以继续结算。 ");
        assertThat(draft.confidence()).isEqualTo(AnswerConfidence.HIGH);
        assertThat(draft.answerBasis()).isEqualTo(AnswerBasis.GROUNDED_APPLICATION);
        assertThat(draft.calculations()).singleElement().satisfies(calculation -> {
            assertThat(calculation.expectedResult()).isEqualByComparingTo("10");
            assertThat(calculation.resultUnit()).isEqualTo("分");
            assertThat(calculation.operands())
                    .extracting(operand -> operand.source())
                    .containsExactly(
                            CalculationOperandSource.QUESTION,
                            CalculationOperandSource.EVIDENCE,
                            CalculationOperandSource.EVIDENCE);
        });
    }

    @Test
    void rejectsUnknownMachineEnumsInsteadOfNormalizingThem() {
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace("GROUNDED_APPLICATION", "grounded-application")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsAFormerExpressionOnlyCalculationShape() {
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft("""
                        {
                          "answerable":true,
                          "insufficiencyReason":null,
                          "shortVerdict":"Two points.",
                          "explanation":"Old expression-only payload.",
                          "citationIds":["%s"],
                          "exceptions":[],
                          "confidence":"HIGH",
                          "answerBasis":"GROUNDED_APPLICATION",
                          "aid":{"type":"CALCULATION","payload":[{"expression":"1 + 1"}]}
                        }
                        """.formatted(citationId)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMissingOrUnexpectedDisplayFieldsInsteadOfDefaultingOrIgnoringThem() {
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace(
                                "  \"explanation\":\"两个完整组合各得五分。\",\n",
                                "")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace(
                                "\"shortVerdict\":\"你得到 10 分。\\n现在可以继续结算。 \",",
                                "\"shortVerdict\":\"你得到 10 分。\\n现在可以继续结算。 \",\"statusLine\":\"完成\",")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replaceFirst(
                                "(?s)\"payload\":\\[.*\\]\\s*\\}\\s*\\}",
                                "\"payload\":null\n  }\n}")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace(
                                "\"citationIds\":[\"%s\"]".formatted(citationId),
                                "\"citationIds\":[\"%s\",\"%s\"]".formatted(citationId, citationId))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void exposesOnlyTheSelectedTypedAidPayloadInTheProviderSchema() {
        String calculation = SpringAiRuleAnswerModel.modelDraftSchema(AnswerAid.CALCULATION);
        String none = SpringAiRuleAnswerModel.modelDraftSchema(AnswerAid.NONE);

        assertThat(calculation)
                .contains("\"aid\"", "\"payload\"", "\"expression\"", "\"CALCULATION\"")
                .doesNotContain("\"calculations\"", "\"walkthroughSteps\"", "\"decisionBranches\"");
        assertThat(none)
                .contains("\"aid\"", "\"NONE\"")
                .doesNotContain("\"payload\"", "\"calculations\"", "\"walkthroughSteps\"");
    }

    @Test
    void salvagesAValidCoreWhenAnOptionalAidIsInvalidOrUsesLegacyArrays() {
        ModelDraft mismatched = SpringAiRuleAnswerModel.parseModelDraft(
                validCalculationJson(), AnswerAid.WALKTHROUGH);
        ModelDraft legacy = SpringAiRuleAnswerModel.parseModelDraft(
                validCalculationJson().replace("\"aid\":{", "\"walkthroughSteps\":[{\"old\":true}],\"aid\":{"),
                AnswerAid.NONE);
        ModelDraft malformedPayload = SpringAiRuleAnswerModel.parseModelDraft(
                validCalculationJson().replaceFirst(
                        "(?s)\"aid\":\\{.*\\}\\s*\\}",
                        "\"aid\":{\"type\":\"WALKTHROUGH\",\"payload\":[{\"instruction\":\"Pay first\"}]}\n}"),
                AnswerAid.WALKTHROUGH);

        assertThat(mismatched.shortVerdict()).isEqualTo("你得到 10 分。\n现在可以继续结算。 ");
        assertThat(mismatched.calculations()).isEmpty();
        assertThat(mismatched.walkthroughSteps()).isEmpty();
        assertThat(legacy.shortVerdict()).isEqualTo(mismatched.shortVerdict());
        assertThat(legacy.walkthroughSteps()).isEmpty();
        assertThat(malformedPayload.shortVerdict()).isEqualTo(mismatched.shortVerdict());
        assertThat(malformedPayload.walkthroughSteps()).isEmpty();
    }

    @Test
    void keepsCalculationAidAndTheCoreContractStrict() {
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace("\"type\":\"CALCULATION\"", "\"type\":\"WALKTHROUGH\""),
                        AnswerAid.CALCULATION))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("structured output contract");
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseModelDraft(
                        validCalculationJson().replace(
                                "  \"explanation\":\"两个完整组合各得五分。\",\n",
                                ""),
                        AnswerAid.NONE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("structured output contract");
    }

    @Test
    void roundTripsARepairDraftWithoutReintroducingLegacyAidArrays() {
        ModelDraft original = SpringAiRuleAnswerModel.parseModelDraft(validCalculationJson(), AnswerAid.CALCULATION);

        String providerDraft = SpringAiRuleAnswerModel.providerDraftJson(original, AnswerAid.CALCULATION);

        assertThat(providerDraft)
                .contains("\"aid\":{\"type\":\"CALCULATION\",\"payload\":[")
                .doesNotContain("\"calculations\":", "\"walkthroughSteps\":");
        assertThat(SpringAiRuleAnswerModel.parseModelDraft(providerDraft, AnswerAid.CALCULATION))
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(original);
    }

    @Test
    void questionPlanningAlsoRequiresOneExactJsonEnvelope() throws Exception {
        String valid = """
                {
                  "questionType":"RULE_QUERY",
                  "referenceBinding":"CURRENT_QUESTION",
                  "terms":["scoring"],
                  "ruleObjectSpans":["the red marker"],
                  "pageHints":[{"questionSpan":"page 4","pageNumber":4}],
                  "missingContext":[],
                  "learningIntent":null,
                  "answerAid":"NONE",
                  "subquestions":[{
                    "questionSpan":"How is the red marker scored?",
                    "evidenceNeeds":["DIRECT_RULE"],
                    "owner":"CURRENT_QUESTION",
                    "retrievalQueries":["red marker scoring"]
                  }]
                }
                """;

        var draft = SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(valid);

        assertThat(draft.ruleObjectSpans()).containsExactly("the red marker");
        assertThat(draft.pageHints()).singleElement().satisfies(hint -> assertThat(hint.pageNumber()).isEqualTo(4));
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(
                        valid.replace("\"answerAid\":\"NONE\",", "")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(
                        valid.replace(
                                "\"questionType\":\"RULE_QUERY\",",
                                "\"questionType\":\"RULE_QUERY\",\"statusLine\":\"done\",")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(
                        valid.replace(
                                "\"questionType\":\"RULE_QUERY\",",
                                "\"questionType\":\"RULE_QUERY\",\"questionType\":\"SITUATION_QUERY\",")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(valid + "{}"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(
                        valid.replace("\"terms\":[\"scoring\"]", "\"terms\":[\"scoring\",\"scoring\"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiRuleAnswerModel.parseQuestionInterpretationDraft(
                        valid.replace(
                                "\"retrievalQueries\":[\"red marker scoring\"]",
                                "\"retrievalQueries\":[\"red marker scoring\",\" red marker scoring \"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    private String validCalculationJson() {
        return """
                {
                  "answerable":true,
                  "insufficiencyReason":null,
                  "shortVerdict":"你得到 10 分。\\n现在可以继续结算。 ",
                  "explanation":"两个完整组合各得五分。",
                  "citationIds":["%s"],
                  "exceptions":[],
                  "confidence":"HIGH",
                  "answerBasis":"GROUNDED_APPLICATION",
                  "aid":{
                    "type":"CALCULATION",
                    "payload":[{
                      "expression":"floor(8 / 3) * 5",
                      "expectedResult":10,
                      "resultUnit":"分",
                      "operands":[
                        {"name":"现有资源","value":8,"source":"QUESTION","sourceSpan":"八个资源","citationId":null},
                        {"name":"每组资源","value":3,"source":"EVIDENCE","sourceSpan":"每三个资源","citationId":"%s"},
                        {"name":"每组分数","value":5,"source":"EVIDENCE","sourceSpan":"获得五分","citationId":"%s"}
                      ]
                    }]
                  }
                }
                """.formatted(citationId, citationId, citationId);
    }
}
