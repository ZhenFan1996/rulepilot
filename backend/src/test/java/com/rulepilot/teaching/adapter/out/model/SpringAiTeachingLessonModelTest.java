package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.InputTokenProfile;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiTeachingLessonModelTest {

    @Test
    void keepsLongFormCompositionOnTheTeachingProviderAfterTheVisualFactsPass() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("deepseek");
        when(configuration.providerFor(Role.VISUAL)).thenReturn("gemini");
        when(configuration.usesFake(Role.VISUAL)).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL)).thenReturn(true);
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.providerId()).isEqualTo("deepseek+gemini");
        assertThat(model.roleFor(request(List.of()))).isEqualTo(Role.TEACHING);
        assertThat(model.roleFor(request(List.of(new PageImageInput(4, "image/png", new byte[] {1}, 100, 100)))))
                .isEqualTo(Role.TEACHING);
    }

    @Test
    void disablesQwenThinkingForBoundedVisualLessonWork() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.VISUAL)).thenReturn("qwen");
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.providerOptions(Role.VISUAL)).containsEntry("enable_thinking", false);
    }

    @Test
    void resolvesVisionFromTheLessonOwnerRatherThanWorkerThreadState() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.VISUAL, "player")).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, "player")).thenReturn(true);
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.supportsVisualEvidence("player")).isTrue();
    }

    @Test
    void resolvesTheVisualModelFromTheLessonOwnerRatherThanWorkerThreadState() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.VISUAL, "player")).thenReturn(false);
        when(configuration.usesFake(Role.VISUAL)).thenReturn(true);
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.usesFake(Role.VISUAL, "player")).isFalse();
    }

    @Test
    void doesNotBudgetRealProviderPromptMaterialForTheFakeAdapter() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.TEACHING)).thenReturn(true);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("fake");
        when(prompts.teachingRuntimeSystem()).thenReturn("A real-provider-only system contract.");
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts);

        InputTokenProfile profile = model.compositionInputProfile(request(List.of()));

        assertThat(profile.providerId()).isEqualTo("fake");
        assertThat(profile.fixedContractTokens()).isZero();
    }

    @Test
    void serializesSectionRequestsWhenEitherAssignedRoleUsesQwen() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("deepseek");
        when(configuration.providerFor(Role.VISUAL, "player")).thenReturn("qwen");
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), mock(VersionedAgentPrompts.class));

        assertThat(model.maxConcurrentSectionRequests("player")).isEqualTo(1);
    }

    @Test
    void appliesConfiguredTemperatureToTeachingComposition() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING)).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.TEACHING, null)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.TEACHING, null)).thenReturn(chatModel);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(prompts.teachingRuntimeSystem()).thenReturn("Teach only from evidence.");
        when(prompts.teachingUser()).thenReturn("{section}\n{objective}\n{evidence}\n{repair}");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"title":"Setup","visualKind":"REFERENCE_CARD","visualCaption":"Source",
                         "visualCitationIds":["E1"],"steps":[{"heading":"Do this","kind":"DO",
                         "text":"Place the board.","citationIds":["E1"]}]}
                        """)))));
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts, 0.28);

        assertThat(model.compose(request(List.of())).steps()).hasSize(1);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getTemperature()).isEqualTo(0.28);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_SCHEMA);
        assertThat(prompt.getValue().getContents())
                .doesNotContain("Your response should be in JSON format", "```json");
        assertThat(model.compositionInputProfile(request(List.of())).totalTokens())
                .isGreaterThanOrEqualTo(estimatedTokens(prompt.getValue().getContents()));
    }

    @Test
    void rejectsInvalidTeachingTemperature() {
        assertThatThrownBy(() -> new SpringAiTeachingLessonModel(
                        mock(RuntimeModelConfiguration.class),
                        new FakeTeachingLessonModel(),
                        mock(VersionedAgentPrompts.class),
                        2.01))
                .hasMessageContaining("teaching model temperature");
    }

    @Test
    void retainsTextualOutputContractWhenTheProviderHasNoNativeTeachingSchema() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING)).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("deepseek");
        when(configuration.modelFor(Role.TEACHING, null)).thenReturn(chatModel);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(prompts.teachingRuntimeSystem()).thenReturn("Teach only from evidence.");
        when(prompts.teachingUser()).thenReturn("{section}\n{objective}\n{evidence}\n{repair}");
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"title":"Setup","visualKind":"REFERENCE_CARD","visualCaption":"Source",
                 "visualCitationIds":["E1"],"steps":[{"heading":"Do this","kind":"DO",
                 "text":"Place the board.","citationIds":["E1"]}]}
                """));
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts);

        assertThat(model.compose(request(List.of())).steps()).hasSize(1);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(sent.capture());
        assertThat(sent.getValue().getContents()).contains("Your response should be in JSON format", "```json");
    }

    @Test
    void qwenSchemaRequiresNonEmptyTeachingPresentationMetadata() throws Exception {
        var properties = new ObjectMapper()
                .readTree(SpringAiTeachingLessonModel.qwenTeachingSchema())
                .path("properties");

        assertThat(properties.path("title").path("minLength").asInt()).isEqualTo(1);
        assertThat(properties.path("visualCaption").path("minLength").asInt()).isEqualTo(1);
        assertThat(properties.path("visualCitationIds").path("minItems").asInt()).isEqualTo(1);
    }

    @Test
    void leavesContractRepairAsAnExplicitSecondProviderAttempt() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING)).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.TEACHING, null)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.TEACHING, null)).thenReturn(chatModel);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(prompts.teachingRuntimeSystem()).thenReturn("Teach only from evidence.");
        when(prompts.teachingUser()).thenReturn("{section}\n{objective}\n{evidence}\n{repair}");
        when(prompts.structuredOutputRepair()).thenReturn("Return one schema-valid object only.");
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("not-json"))
                .thenReturn(response("""
                        {"title":"Setup","visualKind":"REFERENCE_CARD","visualCaption":"Source",
                         "visualCitationIds":["E1"],"steps":[{"heading":"Do this","kind":"DO",
                         "text":"Place the board.","citationIds":["E1"]}]}
                        """));
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts);
        SectionRequest request = request(List.of());

        assertThatThrownBy(() -> model.compose(request)).isInstanceOf(InvalidOutputException.class);
        assertThat(model.repairCompositionContract(request).steps()).hasSize(1);

        ArgumentCaptor<Prompt> promptsSent = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, Mockito.times(2)).call(promptsSent.capture());
        assertThat(promptsSent.getAllValues().getFirst().getContents()).doesNotContain("schema-valid object only");
        assertThat(promptsSent.getAllValues().getLast().getContents()).contains("schema-valid object only");
    }

    @Test
    void doesNotRetryAnInfrastructureFailureInsideTheModelAdapter() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(Role.TEACHING)).thenReturn(false);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("deepseek");
        when(configuration.modelFor(Role.TEACHING, null)).thenReturn(chatModel);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(defaults);
        when(chatModel.getOptions()).thenReturn(defaults);
        when(prompts.teachingRuntimeSystem()).thenReturn("Teach only from evidence.");
        when(prompts.teachingUser()).thenReturn("{section}\n{objective}\n{evidence}\n{repair}");
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider unavailable"));
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts);

        assertThatThrownBy(() -> model.compose(request(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void attributesTheCompleteQwenRequestWithoutPersistingPromptContent() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.providerFor(Role.TEACHING)).thenReturn("qwen");
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("qwen");
        when(prompts.teachingRuntimeSystem()).thenReturn("System contract with private instructions.");
        when(prompts.teachingUser()).thenReturn(
                "Section={section}; objective={objective}; rules={requiredRules}; evidence={evidence}; "
                        + "scope={chapterScope}; continuity={continuity}; repair={repair}; "
                        + "coverage={coverage}; visual={visualEvidenceAvailable}; pages={visualPages}");
        SpringAiTeachingLessonModel model = new SpringAiTeachingLessonModel(
                configuration, new FakeTeachingLessonModel(), prompts);
        SectionRequest request = new SectionRequest(
                "setup",
                "摆放游戏",
                "让玩家完成开局摆放",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(UUID.randomUUID(), "SETUP", "Setup", "Place the board.", 4, 4)),
                List.of(),
                List.of("central board setup"),
                "player",
                "1. 摆放游戏 — 完成开局摆放");

        InputTokenProfile profile = model.compositionInputProfile(request);

        assertThat(profile.providerId()).isEqualTo("qwen");
        assertThat(profile.fixedContractTokens()).isGreaterThanOrEqualTo(
                estimatedTokens("System contract with private instructions.")
                        + estimatedTokens(SpringAiTeachingLessonModel.qwenTeachingSchema()));
        assertThat(profile.objectiveTokens()).isPositive();
        assertThat(profile.requiredRuleTokens()).isPositive();
        assertThat(profile.evidenceTokens()).isPositive();
        assertThat(profile.chapterScopeTokens()).isPositive();
        assertThat(profile.continuityTokens()).isZero();
        assertThat(profile.revisionTokens()).isZero();
        assertThat(profile.totalTokens()).isEqualTo(
                profile.fixedContractTokens()
                        + profile.objectiveTokens()
                        + profile.requiredRuleTokens()
                        + profile.evidenceTokens()
                        + profile.chapterScopeTokens()
                        + profile.continuityTokens()
                        + profile.revisionTokens()
                        + profile.otherRequestTokens());

        SectionDraft draft = new FakeTeachingLessonModel().compose(request);
        assertThat(model.estimatedOutputTokens(request, draft))
                .isLessThan(estimatedTokens(draft.toString()));
    }

    private int estimatedTokens(String value) {
        return Math.max(1, (value.length() + 3) / 4);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private SectionRequest request(List<PageImageInput> pageImages) {
        return new SectionRequest(
                "setup",
                "摆放游戏",
                "让玩家完成开局摆放",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(UUID.randomUUID(), "SETUP", "Setup", "Place the board.", 4, 4)),
                pageImages);
    }
}
