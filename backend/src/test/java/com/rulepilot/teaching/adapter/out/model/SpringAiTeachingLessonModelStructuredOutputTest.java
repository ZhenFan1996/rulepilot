package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import org.junit.jupiter.api.Test;

class SpringAiTeachingLessonModelStructuredOutputTest {

    private static final String VALID = """
            {
              "title":"开始行动",
              "visualKind":"FLOW_DIAGRAM",
              "visualCaption":"从选择行动到结算结果的顺序。",
              "visualCitationIds":["E1"],
              "steps":[{
                "heading":"选择一项行动",
                "kind":"DO",
                "text":"选择当前可用的一项行动，然后支付对应费用。",
                "citationIds":["E1"],
                "teachingUnitIds":["turn-action"],
                "ruleFacts":[
                  {"role":"CHOICE","text":"选择一项当前可用的行动。","citationIds":["E1"]},
                  {"role":"COST_OR_GAIN","text":"支付该行动列出的费用。","citationIds":["E1"]},
                  {"role":"LIMIT","text":"本回合只能选择一项行动。","citationIds":["E1"]}
                ]
              }]
            }
            """;

    @Test
    void advertisesOnlyTheTextCapabilityItActuallyUsesForSectionComposition() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("deepseek");
        SpringAiTeachingLessonModel model =
                new SpringAiTeachingLessonModel(configuration, mock(VersionedAgentPrompts.class));

        assertThat(model.providerId()).isEqualTo("deepseek");
        assertThat(model.supportsVisualEvidence()).isFalse();
        assertThat(model.supportsVisualEvidence("player")).isFalse();
    }

    @Test
    void admitsEveryDeclaredDisplayFieldFromOneExactJsonObject() throws Exception {
        var draft = SpringAiTeachingLessonModel.parseStructuredDraft(VALID);

        assertThat(draft.title()).isEqualTo("开始行动");
        assertThat(draft.steps()).singleElement().satisfies(step -> {
            assertThat(step.heading()).isEqualTo("选择一项行动");
            assertThat(step.ruleFacts()).extracting(SpringAiTeachingLessonModel.ModelRuleFact::role)
                    .containsExactly(
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.CHOICE,
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.COST_OR_GAIN,
                            com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole.LIMIT);
        });
    }

    @Test
    void admitsAtTableReferenceAndLimitStepsWithoutRegeneratingSupportedContent() throws Exception {
        var reference = SpringAiTeachingLessonModel.parseStructuredDraft(
                VALID.replace("\"kind\":\"DO\"", "\"kind\":\"REFERENCE_CARD\""));
        var limit = SpringAiTeachingLessonModel.parseStructuredDraft(
                VALID.replace("\"kind\":\"DO\"", "\"kind\":\"LIMIT\""));

        assertThat(reference.steps().getFirst().kind())
                .isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.REFERENCE_CARD);
        assertThat(limit.steps().getFirst().kind())
                .isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.LIMIT);
    }

    @Test
    void preservesVisualIntentWithoutAskingTheTextModelForGeometry() throws Exception {
        var visual = SpringAiTeachingLessonModel.parseStructuredDraft(
                VALID.replace("\"kind\":\"DO\"", "\"kind\":\"VISUAL\""));

        assertThat(visual.steps().getFirst().kind())
                .isEqualTo(com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.VISUAL);
        assertThat(SpringAiTeachingLessonModel.qwenTeachingSchema()).doesNotContain("visualFocus");
    }

    @Test
    void rejectsMissingNestedFieldsInsteadOfDefaultingThemToEmptyLists() {
        String missingRuleFacts = """
                {
                  "title":"开始行动",
                  "visualKind":"FLOW_DIAGRAM",
                  "visualCaption":"从选择行动到结算结果的顺序。",
                  "visualCitationIds":["E1"],
                  "steps":[{
                    "heading":"选择一项行动",
                    "kind":"DO",
                    "text":"选择当前可用的一项行动。",
                    "citationIds":["E1"],
                    "teachingUnitIds":["turn-action"]
                  }]
                }
                """;

        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(missingRuleFacts))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsUnexpectedFieldsAndMarkdownWrappersInsteadOfRepairingThem() {
        String unexpected = VALID.replace("\"title\":\"开始行动\"", "\"title\":\"开始行动\",\"statusLine\":\"完成\"");
        String obsoleteGeometry = VALID.replace(
                "\"ruleFacts\":[",
                "\"visualFocus\":null,\"ruleFacts\":[");

        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(unexpected))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(obsoleteGeometry))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft("```json\n" + VALID + "\n```"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsNullOrDuplicateStructuredArraysInsteadOfNormalizingThem() {
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"visualCitationIds\":[\"E1\"]", "\"visualCitationIds\":null")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"citationIds\":[\"E1\"]", "\"citationIds\":[\"E1\",\"E1\"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiTeachingLessonModel.parseStructuredDraft(
                        VALID.replace("\"teachingUnitIds\":[\"turn-action\"]",
                                "\"teachingUnitIds\":[\"turn-action\",\"turn-action\"]")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
