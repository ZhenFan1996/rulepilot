package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Evidence;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiContentCriticModelTest {

    @Test
    void disablesQwenThinkingAndAppliesTheConfiguredCriticTemperature() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.criticSystem()).thenReturn("Check every claim against the evidence.");
        when(prompts.criticUser()).thenReturn(
                "Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nReturn {\"issues\":[]}.\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return valid JSON.");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts, 0.35);

        UUID evidenceId = UUID.randomUUID();
        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Explain the ending.", "All terminal branches.", 1),
                List.of(new Claim(0, "The game ends after the last round.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "After the last round, the game ends."))));

        assertThat(result.issues()).isEmpty();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.7-plus");
        assertThat(options.getTemperature()).isEqualTo(0.35);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getExtraBody()).containsExactlyEntriesOf(java.util.Map.of("enable_thinking", false));
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("The game ends after the last round.")
                        && text.contains("Return {\"issues\":[]}.")
                        && !text.contains("⟦claims⟧"));
    }

    @Test
    void disablesDeepSeekThinkingForAtomicLessonConfirmation() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("deepseek");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("deepseek-chat");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.CRITIC)).thenReturn(true);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.atomicCriticSystem()).thenReturn("Confirm only candidate defects.");
        when(prompts.atomicCriticUser())
                .thenReturn("SUPPORTED_VERDICT Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return valid JSON.");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"issues\":[{\"defectConfirmed\":false,\"type\":\"MISSING_EXCEPTION\","
                        + "\"claimAspect\":\"GENERAL\",\"claimPosition\":1,\"evidenceIds\":[\"E1\"],"
                        + "\"summary\":\"The complete meanings align.\"}]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts, 0.0);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext("Confirm one candidate.", "1=[MISSING_EXCEPTION]", 1),
                List.of(new Claim(1, "The winner starts next.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "The winner starts next unless out of cards."))));

        assertThat(result.issues()).isEmpty();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("SUPPORTED_VERDICT"));
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-chat");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getExtraBody())
                .containsExactlyEntriesOf(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void representsANegativeVerdictWithTheExactEmptyIssuesEnvelope() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("deepseek");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("deepseek-chat");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.CRITIC)).thenReturn(true);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.atomicCriticSystem()).thenReturn("Confirm only candidate defects.");
        when(prompts.atomicCriticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return every required issue field or an empty issues list.");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext("Confirm one candidate.", "1=[UNSUPPORTED_CLAIM]", 1),
                List.of(new Claim(1, "Claim.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "Evidence."))));

        assertThat(result.issues()).isEmpty();
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void givesAnEmptyCriticResponseOneFinalBoundedSchemaRetry() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.criticSystem()).thenReturn("Check every claim against the evidence.");
        when(prompts.criticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return valid JSON.");
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalArgumentException("No content to map due to end-of-input"))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Explain the ending.", "All terminal branches.", 1),
                List.of(new Claim(0, "The game ends after the last round.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "After the last round, the game ends."))));

        assertThat(result.issues()).isEmpty();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void givesAnInterruptedResponseReadOneFinalBoundedSchemaRetry() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.criticSystem()).thenReturn("Check every claim against the evidence.");
        when(prompts.criticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return valid JSON.");
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("Error reading response"))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Explain the ending.", "All terminal branches.", 1),
                List.of(new Claim(0, "The game ends after the last round.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "After the last round, the game ends."))));

        assertThat(result.issues()).isEmpty();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void givesAnIncompleteIssueSchemaOneFinalBoundedRetry() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("deepseek");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("deepseek-chat");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.CRITIC)).thenReturn(true);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.atomicCriticSystem()).thenReturn("Confirm only candidate defects.");
        when(prompts.atomicCriticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.structuredOutputRepair()).thenReturn("Return every required issue field or an empty issues list.");
        when(prompts.criticOutputRepair()).thenReturn(
                "Return an empty issues array for no defect; every retained issue requires a type.");
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"issues\":[{\"defectConfirmed\":true,\"claimPosition\":1,"
                                + "\"evidenceIds\":[\"E1\"],\"summary\":\"Candidate.\"}]}")))))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext("Confirm one candidate.", "1=[UNSUPPORTED_CLAIM]", 1),
                List.of(new Claim(1, "Claim.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "Evidence."))));

        assertThat(result.issues()).isEmpty();
        verify(chatModel, times(2)).call(any(Prompt.class));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompt.capture());
        assertThat(prompt.getAllValues().getLast().getInstructions())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("every retained issue requires a type"));
    }

    @Test
    void repairsAConfirmedLessonDefectThatOmitsItsClaimAspect() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("deepseek");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("deepseek-chat");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.CRITIC)).thenReturn(true);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-chat")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.atomicCriticSystem()).thenReturn("Confirm the exact candidate claim aspect.");
        when(prompts.atomicCriticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.criticOutputRepair()).thenReturn("Every confirmed lesson issue requires claimAspect.");
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"issues\":[{\"defectConfirmed\":true,\"type\":\"CONTRADICTION\","
                                + "\"claimPosition\":1,\"evidenceIds\":[\"E1\"],"
                                + "\"summary\":\"The interval changed.\"}]}")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"issues\":[{\"defectConfirmed\":true,\"type\":\"CONTRADICTION\","
                                + "\"claimAspect\":\"TIMING\",\"claimPosition\":1,"
                                + "\"evidenceIds\":[\"E1\"],\"summary\":\"The interval changed.\"}]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext("Confirm one opaque procedure.", "1=[CONTRADICTION/TIMING]", 1),
                List.of(new Claim(1, "The vek keeper seals the luma after the interval.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "The vek keeper seals the luma during the interval."))));

        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.claimAspect()).isEqualTo(ClaimAspect.TIMING);
            assertThat(issue.evidenceIds()).containsExactly(evidenceId);
        });
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void repairsAConfirmedLessonDefectThatHasNoClaimBoundEvidence() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(configuration.usesFake(Role.CRITIC)).thenReturn(false);
        when(configuration.providerFor(Role.CRITIC)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.CRITIC)).thenReturn("qwen3.7-plus");
        when(configuration.modelFor(Role.CRITIC)).thenReturn(chatModel);
        OpenAiChatOptions providerOptions = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(providerOptions);
        when(chatModel.getOptions()).thenReturn(providerOptions);
        when(prompts.criticSystem()).thenReturn("Review the exact claim aspect and evidence binding.");
        when(prompts.criticUser()).thenReturn("Claims: ⟦claims⟧\nEvidence: ⟦evidence⟧\nRepair: ⟦repair⟧");
        when(prompts.criticOutputRepair()).thenReturn("Every confirmed lesson issue requires relevant evidenceIds.");
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"issues\":[{\"defectConfirmed\":true,\"type\":\"CONTRADICTION\","
                                + "\"claimAspect\":\"SUBJECT\",\"claimPosition\":1,\"evidenceIds\":[],"
                                + "\"summary\":\"The acting keeper changed.\"}]}")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"issues\":[]}")))));
        SpringAiContentCriticModel model = new SpringAiContentCriticModel(
                configuration, prompts);
        UUID evidenceId = UUID.randomUUID();

        var result = model.critique(new ReviewRequest(
                UUID.randomUUID(),
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext("Teach one opaque procedure.", "Preserve its actor.", 1),
                List.of(new Claim(1, "The toro keeper opens the nari.", List.of(evidenceId))),
                List.of(new Evidence(evidenceId, "The vek keeper opens the nari."))));

        assertThat(result.issues()).isEmpty();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void rejectsInvalidCriticTemperature() {
        assertThatThrownBy(() -> new SpringAiContentCriticModel(
                        mock(RuntimeModelConfiguration.class),
                        mock(VersionedAgentPrompts.class),
                        -0.01))
                .hasMessageContaining("critic model temperature");
    }
}
