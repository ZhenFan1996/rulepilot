package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.ModelProviderProperties.Provider;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
                "player", "deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
        RuntimeModelConfiguration.Snapshot assigned =
                configuration.assign("player", "deepseek", "deepseek", "fake");

        assertThat(configured.toString()).doesNotContain("secret-value");
        assertThat(assigned.assignments().teaching()).isEqualTo("deepseek");
        assertThat(configuration.snapshot("someone-else").assignments().teaching()).isEqualTo("fake");
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("player", "", java.util.List.of()));
            assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING)).isEqualTo("deepseek");
            assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER)).isSameAs(model);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(configuration.disable("player", "deepseek").assignments().teaching()).isEqualTo("fake");
        verify(factory).create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
    }
}
