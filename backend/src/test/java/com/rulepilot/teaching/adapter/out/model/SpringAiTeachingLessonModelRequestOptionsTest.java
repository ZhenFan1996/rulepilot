package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiTeachingLessonModelRequestOptionsTest {

    @Test
    void preservesTheResolvedTimeoutForADeepSeekNonThinkingSection() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        ChatModel model = mock(ChatModel.class);
        Duration configuredTimeout = Duration.ofMinutes(5);
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model("teaching-test-model")
                .timeout(configuredTimeout)
                .build();
        when(model.getDefaultOptions()).thenReturn(defaults);
        when(model.getOptions()).thenReturn(defaults);
        when(model.call(any(Prompt.class))).thenReturn(response());
        when(configuration.usesFake(Role.TEACHING, "player")).thenReturn(false);
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(model);
        when(configuration.modelNameFor(Role.TEACHING, "player")).thenReturn("teaching-test-model");
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("deepseek");
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.TEACHING, "player"))
                .thenReturn(true);
        when(prompts.teachingRuntimeSystem()).thenReturn("Teach only supported rules.");
        when(prompts.teachingUser()).thenReturn("""
                Section={section}
                Objective={objective}
                Prior={continuity}
                Evidence={evidence}
                Repair={repair}
                """);

        var section = new SpringAiTeachingLessonModel(configuration, prompts).compose(request());

        assertThat(section.steps()).singleElement();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getTimeout()).isEqualTo(configuredTimeout);
        assertThat(options.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    private static SectionRequest request() {
        return new SectionRequest(
                "take-turn",
                "进行回合",
                "教会玩家完成一个回合。",
                List.of(),
                List.of(new EvidenceInput(
                        UUID.randomUUID(),
                        "RULE",
                        "Take a turn",
                        "Move one marker, then pass play clockwise.",
                        3,
                        3)),
                "player");
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"locale":"zh-CN","title":"进行回合","steps":[{"heading":"移动并交棒","kind":"DO",
                "text":"先移动一个标记，再把回合交给下一位玩家。","citationIds":["E1"],"ruleFacts":[]}]}
                """))));
    }
}
