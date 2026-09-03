package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.adapter.out.model.SpringAiLessonLocalizationModel.SectionTranslationDraft;
import com.rulepilot.teaching.adapter.out.model.SpringAiLessonLocalizationModel.RuleFactTranslationDraft;
import com.rulepilot.teaching.adapter.out.model.SpringAiLessonLocalizationModel.StepTranslationDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFact;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.LessonLocalization.RuleFactTranslation;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiLessonLocalizationModelTest {

    @Test
    void requestsStrictJsonAndPreservesTimeoutForDeepSeekLocalization() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        Duration timeout = Duration.ofMinutes(4);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model("deepseek-localization")
                .timeout(timeout)
                .build();
        LessonSection source = textSection();
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.resolvedModelFor(Role.TEACHING, "player"))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "deepseek", "deepseek-localization", true));
        when(chatModel.getOptions()).thenReturn(defaults);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"position":1,"title":"Setup","visualCaption":"","steps":[{"position":1,
                        "heading":"Check","text":"Check the count.","visualLabel":"",
                        "visualDescription":"","ruleFacts":[]}]}
                        """)))));
        when(prompts.lessonLocalizationSystem()).thenReturn("Translate and return JSON only.");
        when(prompts.lessonLocalizationUser()).thenReturn("Target={targetLanguage}\nSection={section}");

        new SpringAiLessonLocalizationModel(configuration, prompts).translate(source, PlayerLocale.EN, "player");

        ArgumentCaptor<Prompt> request = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(request.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) request.getValue().getOptions();
        assertThat(options.getTimeout()).isEqualTo(timeout);
        assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void preservesCompleteVisualProseAndTranslatesStructuredRuleFacts() {
        UUID evidenceId = UUID.randomUUID();
        LessonSection source = new LessonSection(
                1,
                "setup",
                List.of("setup"),
                "设置",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.TABLE_LAYOUT,
                "查看桌面。",
                List.of(2),
                List.of(),
                List.of(
                        new LessonStep(
                                1,
                                "摆放",
                                TeachingMove.VISUAL,
                                "摆好组件。",
                                List.of(2),
                                List.of(evidenceId),
                                List.of(new RuleFact(
                                        1,
                                        RuleFactRole.ACTION,
                                        "把主棋盘放在桌面中央。",
                                        List.of(2),
                                        List.of(evidenceId))),
                                new VisualFocus(2, "主棋盘", "图中显示主棋盘和周围组件。", 100, 100, 300, 300)),
                        new LessonStep(
                                2,
                                "检查",
                                TeachingMove.CHECK,
                                "检查数量。",
                                List.of(2),
                                List.of(),
                                null)));
        String longDescription = "This crop shows the board and every nearby component in a deliberately verbose "
                .repeat(6);
        SectionTranslationDraft draft = new SectionTranslationDraft(
                1,
                "Setup",
                "Look at the table.",
                List.of(
                        new StepTranslationDraft(
                                1,
                                "Place",
                                "Place the components.",
                                "Main board",
                                longDescription,
                                List.of(new RuleFactTranslationDraft(
                                        1, "Place the main board in the center of the table."))),
                        new StepTranslationDraft(
                                2,
                                "Check",
                                "Check the count.",
                                "",
                                "",
                                List.of())));

        var translated = SpringAiLessonLocalizationModel.toDomain(source, draft);

        assertThat(translated.steps().getFirst().visualDescription()).isEqualTo(longDescription.strip());
        assertThat(translated.steps().getFirst().ruleFacts())
                .extracting(RuleFactTranslation::text)
                .containsExactly("Place the main board in the center of the table.");
        assertThat(translated.steps().get(1).visualLabel()).isEmpty();
        assertThat(translated.steps().get(1).visualDescription()).isEmpty();
    }

    @Test
    void parsesOneTranslationEnvelopeWithAdditiveMetadataWithoutDefaultingRequiredFields() throws Exception {
        String valid = """
                {
                  "position":1,
                  "title":"Setup",
                  "visualCaption":"Look at the table.",
                  "steps":[{
                    "position":1,
                    "heading":"Place the board",
                    "text":"Place the board in the center.",
                    "visualLabel":"Main board",
                    "visualDescription":"The board is centered between the player areas.",
                    "ruleFacts":[{"position":1,"text":"Place the board in the center of the table."}]
                  }]
                }
                """;

        var draft = SpringAiLessonLocalizationModel.parseSectionTranslation(valid);

        assertThat(draft.steps()).singleElement().satisfies(step ->
                assertThat(step.ruleFacts()).singleElement().satisfies(fact ->
                        assertThat(fact.text()).isEqualTo("Place the board in the center of the table.")));
        assertThat(SpringAiLessonLocalizationModel.parseSectionTranslation(
                        valid.replace("\"ruleFacts\":[{", "\"statusLine\":\"done\",\"ruleFacts\":[{")).steps())
                .singleElement();
        assertThatThrownBy(() -> SpringAiLessonLocalizationModel.parseSectionTranslation(
                        valid.replace(
                                "\"visualDescription\":\"The board is centered between the player areas.\",\n",
                                "")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> SpringAiLessonLocalizationModel.parseSectionTranslation("```json\n" + valid + "\n```"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void rejectsInventedVisualFieldsInsteadOfIgnoringThemForANonVisualStep() {
        LessonSection source = new LessonSection(
                1,
                "check",
                List.of(),
                "检查",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.FLOW_DIAGRAM,
                "",
                List.of(),
                List.of(),
                List.of(new LessonStep(
                        1,
                        "检查",
                        TeachingMove.CHECK,
                        "检查数量。",
                        List.of(1),
                        List.of(),
                        null)));
        SectionTranslationDraft draft = new SectionTranslationDraft(
                1,
                "Check",
                "",
                List.of(new StepTranslationDraft(
                        1,
                        "Check",
                        "Check the count.",
                        "invented label",
                        "invented description",
                        List.of())));

        assertThatThrownBy(() -> SpringAiLessonLocalizationModel.toDomain(source, draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visual fields");
    }

    private static LessonSection textSection() {
        return new LessonSection(
                1,
                "check",
                List.of(),
                "检查",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "",
                List.of(1),
                List.of(),
                List.of(new LessonStep(
                        1,
                        "检查",
                        TeachingMove.CHECK,
                        "检查数量。",
                        List.of(1),
                        List.of(),
                        null)));
    }
}
