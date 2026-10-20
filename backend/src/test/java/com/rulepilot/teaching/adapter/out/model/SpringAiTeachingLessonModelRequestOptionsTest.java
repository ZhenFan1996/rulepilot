package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.shared.PlayerLocale;
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

    @Test
    void transportsVisualIdentityDescriptionAndEvidenceValuesToTheWriter() {
        var configuration = mock(RuntimeModelConfiguration.class);
        var prompts = mock(VersionedAgentPrompts.class);
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        when(configuration.modelFor(Role.TEACHING, "player")).thenReturn(model);
        when(configuration.providerFor(Role.TEACHING, "player")).thenReturn("deepseek");
        when(model.call(any(Prompt.class))).thenReturn(response());
        when(prompts.teachingRuntimeSystem()).thenReturn("Write a grounded chapter.");
        when(prompts.teachingUser()).thenReturn("{evidence}\n{visuals}");
        var base = request();
        var focus = new com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus(
                3, "Marker", "A blue marker sits beside a clockwise arrow.", 100, 100, 300, 300);
        var request = new SectionRequest(base.topicKey(), base.title(), base.objective(), base.priorSections(),
                base.evidence(), List.of(new com.rulepilot.teaching.TeachingLessonModel.VisualInput(
                        "owned-image", focus, List.of(base.evidence().getFirst().chunkId()))), "player", PlayerLocale.ZH_CN);
        new SpringAiTeachingLessonModel(configuration, prompts).compose(request);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        assertThat(captured.getValue().getUserMessage().getText())
                .contains("owned-image", focus.visibleDescription(), "E1");
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
                "player", PlayerLocale.ZH_CN);
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"locale":"zh-CN","title":"进行回合","steps":[{"heading":"移动并交棒","kind":"DO",
                "text":"先移动一个标记，再把回合交给下一位玩家。","citationIds":["E1"],"ruleFacts":[]}]}
                """))));
    }
}
