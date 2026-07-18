package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.ModelProviderProperties.Provider;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class RuntimeModelConfigurationTest {

    @Test
    void configuresAssignsAndDisablesProviderWithoutReturningSecret() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(factory.create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash"))
                .thenReturn(model);
        Provider disabled = new Provider(false, "", "", "");
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled),
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini");

        RuntimeModelConfiguration.Snapshot configured = configuration.configure(
                "deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
        configuration.assign("deepseek", "deepseek", "fake");

        assertThat(configured.toString()).doesNotContain("secret-value");
        assertThat(configuration.providerFor(Role.TEACHING)).isEqualTo("deepseek");
        assertThat(configuration.modelFor(Role.ANSWER)).isSameAs(model);
        assertThat(configuration.disable("deepseek").assignments().teaching()).isEqualTo("fake");
        verify(factory).create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
    }
}
