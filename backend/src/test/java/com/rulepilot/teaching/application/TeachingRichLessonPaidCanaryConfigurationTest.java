package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class TeachingRichLessonPaidCanaryConfigurationTest {

    @Test
    void deepSeekCanaryUsesTheSameNonThinkingResolvedModeAsProductionTeaching() {
        var configuration = TeachingRichLessonPaidCanaryTest.configuration(
                "deepseek", "deepseek-test-model", mock(ChatModel.class));

        assertThat(configuration.resolvedModelFor(Role.TEACHING, "teaching-agent-canary")
                        .deepSeekNonThinkingGeneration())
                .isTrue();
        assertThat(configuration.usesDeepSeekNonThinkingGeneration(
                        Role.TEACHING, "teaching-agent-canary"))
                .isTrue();
    }

    @Test
    void anotherProviderDoesNotReceiveDeepSeekPrivateOptions() {
        var configuration = TeachingRichLessonPaidCanaryTest.configuration(
                "qwen", "qwen-test-model", mock(ChatModel.class));

        assertThat(configuration.resolvedModelFor(Role.TEACHING, "teaching-agent-canary")
                        .deepSeekNonThinkingGeneration())
                .isFalse();
        assertThat(configuration.usesDeepSeekNonThinkingGeneration(
                        Role.TEACHING, "teaching-agent-canary"))
                .isFalse();
    }
}
