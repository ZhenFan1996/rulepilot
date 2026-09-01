package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiLessonLocalizationModelTest {

    @Test
    void tracesEachSuccessfulLocalizationProviderAttemptWithItsRawTurn() {
        String translated = """
                {"position":1,"title":"Check","visualCaption":"","steps":[
                {"position":1,"heading":"Check","text":"Check the count.",
                "visualLabel":"","visualDescription":"","ruleFacts":[]}]}
                """;
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(translated));
        RecordingCapture capture = new RecordingCapture();
        UUID operationId = UUID.randomUUID();

        localizationModel(chatModel).translate(
                localizableSection(),
                PlayerLocale.EN,
                "owner",
                capture,
                context(operationId),
                2);

        assertThat(capture.starts).singleElement().satisfies(start -> {
            assertThat(start.attempt()).isEqualTo(2);
            assertThat(start.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.turns).singleElement().satisfies(turn -> {
            assertThat(turn.assistantText()).isEqualTo(translated);
            assertThat(turn.attempt()).isEqualTo(2);
            assertThat(turn.context().operationId()).isEqualTo(operationId);
        });
        assertThat(capture.failures).isEmpty();
    }

    @Test
    void correlatesLocalizationProviderFailureWithoutInventingAModelTurn() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("private localization provider detail"));
        RecordingCapture capture = new RecordingCapture();
        UUID operationId = UUID.randomUUID();

        assertThatThrownBy(() -> localizationModel(chatModel).translate(
                        localizableSection(),
                        PlayerLocale.EN,
                        "owner",
                        capture,
                        context(operationId),
                        1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(capture.starts).singleElement()
                .extracting(start -> start.context().operationId())
                .isEqualTo(operationId);
        assertThat(capture.turns).isEmpty();
        assertThat(capture.failures).singleElement().satisfies(failure -> {
            assertThat(failure.signal()).isEqualTo(LifecycleSignal.FAILURE);
            assertThat(failure.code()).isEqualTo("LESSON_LOCALIZATION_MODEL_FAILED");
            assertThat(failure.context().operationId()).isEqualTo(operationId);
        });
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
    void parsesOneExactTranslationEnvelopeWithoutDefaultingNestedFields() throws Exception {
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
        assertThatThrownBy(() -> SpringAiLessonLocalizationModel.parseSectionTranslation(
                        valid.replace("\"ruleFacts\":[{", "\"statusLine\":\"done\",\"ruleFacts\":[{")))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
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

    private SpringAiLessonLocalizationModel localizationModel(ChatModel chatModel) {
        RuntimeModelConfiguration models = mock(RuntimeModelConfiguration.class);
        when(models.modelFor(Role.TEACHING, "owner")).thenReturn(chatModel);
        when(models.providerFor(Role.TEACHING, "owner")).thenReturn("test-provider");
        when(models.modelNameFor(Role.TEACHING, "owner")).thenReturn("test-model");
        when(models.usesFake(Role.TEACHING, "owner")).thenReturn(false);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(prompts.lessonLocalizationSystem()).thenReturn("Return exact localization JSON.");
        when(prompts.lessonLocalizationUser()).thenReturn("Translate {section} to {targetLanguage}.");
        return new SpringAiLessonLocalizationModel(models, prompts);
    }

    private LessonSection localizableSection() {
        return new LessonSection(
                1,
                "check",
                List.of("core_loop"),
                "检查",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.FLOW_DIAGRAM,
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

    private TraceEventContext context(UUID operationId) {
        return TraceEventContext.create(
                Instant.now(),
                JourneyStage.TEACHING,
                operationId,
                UUID.randomUUID(),
                new ResourceRef(ResourceType.LOCALIZATION_RUN, UUID.randomUUID()));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class RecordingCapture implements CaptureHandle {
        private final UUID traceId = UUID.randomUUID();
        private final List<AgentTraceEvent.ModelCallStarted> starts = new ArrayList<>();
        private final List<AgentTraceEvent.ModelTurn> turns = new ArrayList<>();
        private final List<AgentTraceEvent.BindingOrFailure> failures = new ArrayList<>();

        @Override public boolean enabled() { return true; }
        @Override public Optional<UUID> traceId() { return Optional.of(traceId); }
        @Override public void userTurn(AgentTraceEvent.UserTurn event) {}
        @Override public void modelCallStarted(AgentTraceEvent.ModelCallStarted event) { starts.add(event); }
        @Override public void modelTurn(AgentTraceEvent.ModelTurn event) { turns.add(event); }
        @Override public void toolCall(AgentTraceEvent.ToolCall event) {}
        @Override public void toolObservation(AgentTraceEvent.ToolObservation event) {}
        @Override public void publication(AgentTraceEvent.Publication event) {}
        @Override public void bindingOrFailure(AgentTraceEvent.BindingOrFailure event) { failures.add(event); }
        @Override public boolean bind(ResourceRef resource) { return true; }
    }
}
