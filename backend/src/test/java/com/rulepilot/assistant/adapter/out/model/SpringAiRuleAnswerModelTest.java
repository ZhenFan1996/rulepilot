package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class SpringAiRuleAnswerModelTest {

    @Test
    void selectsConfiguredProviderWithoutCallingExternalApi() {
        ChatModel deepSeek = mock(ChatModel.class);

        SpringAiRuleAnswerModel model = new SpringAiRuleAnswerModel(Map.of("deepseek", deepSeek), " DeepSeek ");

        assertThat(model.providerId()).isEqualTo("deepseek");
    }
}
