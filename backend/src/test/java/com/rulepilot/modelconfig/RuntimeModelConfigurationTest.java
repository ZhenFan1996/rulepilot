package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        ChatModel visualModel = mock(ChatModel.class);
        when(factory.create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash"))
                .thenReturn(model);
        when(factory.create("gemini", "gemini-secret", "", "gemini-2.5-flash"))
                .thenReturn(visualModel);
        Provider disabled = new Provider(false, "", "", "");
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled),
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                false);
        assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.TEACHING)).isFalse();

        RuntimeModelConfiguration.Snapshot configured = configuration.configure(
                "player", "deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
        configuration.configure("player", "gemini", "gemini-secret", "", "gemini-2.5-flash");
        assertThatThrownBy(() -> configuration.assign("player", "deepseek", "deepseek", "deepseek", "fake"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must support page images");
        RuntimeModelConfiguration.Snapshot assigned =
                configuration.assign("player", "deepseek", "gemini", "deepseek", "deepseek");

        assertThat(configured.toString()).doesNotContain("secret-value");
        assertThat(assigned.assignments().teaching()).isEqualTo("deepseek");
        assertThat(assigned.assignments().visual()).isEqualTo("gemini");
        assertThat(configuration.snapshot("someone-else").assignments().teaching()).isEqualTo("fake");
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("player", "", java.util.List.of()));
            assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.TEACHING)).isEqualTo("deepseek");
            assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.TEACHING)).isFalse();
            assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL)).isEqualTo("gemini");
            assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL)).isTrue();
            assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL)).isSameAs(visualModel);
            assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER)).isSameAs(model);
            assertThat(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.TEACHING))
                    .isTrue();
            assertThat(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.ANSWER))
                    .isTrue();
            assertThat(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC))
                    .isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(configuration.disable("player", "deepseek").assignments().teaching()).isEqualTo("fake");
        verify(factory).create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
        verify(factory).create("gemini", "gemini-secret", "", "gemini-2.5-flash");
    }
}
