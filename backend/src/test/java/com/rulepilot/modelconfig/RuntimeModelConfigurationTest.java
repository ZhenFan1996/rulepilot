package com.rulepilot.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.ModelProviderProperties.Provider;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import java.util.List;
import java.util.Optional;
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
        Provider disabled = new Provider(false, "", "", "", false);
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled, disabled),
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "qwen",
                false,
                "");
        assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.TEACHING)).isFalse();

        RuntimeModelConfiguration.Snapshot configured = configuration.configure(
                "player", "deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash", false);
        configuration.configure("player", "gemini", "gemini-secret", "", "gemini-2.5-flash", true);
        assertThatThrownBy(() -> configuration.assign(
                        "player", "deepseek", "deepseek", "deepseek", "fake", "deepseek"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must support page images");
        RuntimeModelConfiguration.Snapshot assigned =
                configuration.assign(
                        "player", "deepseek", "gemini", "deepseek", "deepseek", "fake");

        assertThat(configured.toString()).doesNotContain("secret-value");
        assertThat(assigned.assignments().teaching()).isEqualTo("deepseek");
        assertThat(assigned.assignments().visual()).isEqualTo("gemini");
        assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL, "player")).isTrue();
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL, "player")).isSameAs(visualModel);
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
            assertThat(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).isTrue();
            assertThat(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC))
                    .isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(configuration.disable("player", "deepseek").assignments().teaching()).isEqualTo("fake");
        verify(factory).create("deepseek", "secret-value", "https://api.deepseek.com", "deepseek-v4-flash");
        verify(factory).create("gemini", "gemini-secret", "", "gemini-2.5-flash");
    }

    @Test
    void assignsTheRecommendationRoleIndependentlyFromRuleAnswering() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel qwenModel = mock(ChatModel.class);
        Provider disabled = new Provider(false, "", "", "", false);
        Provider qwen = new Provider(
                true,
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                false);
        when(factory.create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus"))
                .thenReturn(qwenModel);

        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, qwen, disabled),
                "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                "spring-ai", "qwen", false, "");

        assertThat(configuration.usesFake(RuntimeModelConfiguration.Role.ANSWER)).isTrue();
        assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).isEqualTo("qwen");
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).isSameAs(qwenModel);
    }

    @Test
    void overridesOnlyTheStartupRecommendationModelForItsSelectedProvider() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel sharedQwen = mock(ChatModel.class);
        ChatModel recommendationQwen = mock(ChatModel.class);
        Provider disabled = new Provider(false, "", "", "", false);
        Provider qwen = new Provider(
                true,
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                true);
        when(factory.create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus"))
                .thenReturn(sharedQwen);
        when(factory.create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.8-flash"))
                .thenReturn(recommendationQwen);

        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, qwen, disabled),
                "fake", "gemini", "spring-ai", "qwen", "spring-ai", "qwen", "fake", "gemini",
                "spring-ai", "qwen", "qwen3.8-flash", false, "service-user");

        assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isEqualTo("qwen");
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isSameAs(recommendationQwen);
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isEqualTo("qwen3.8-flash");
        assertThat(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .satisfies(resolved -> {
                    assertThat(resolved.model()).isSameAs(recommendationQwen);
                    assertThat(resolved.provider()).isEqualTo("qwen");
                    assertThat(resolved.modelName()).isEqualTo("qwen3.8-flash");
                    assertThat(resolved.deepSeekNonThinkingGeneration()).isFalse();
                });
        assertThat(configuration.effectiveModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isEqualTo(new RuntimeModelConfiguration.EffectiveModel("qwen", "qwen3.8-flash"));
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL)).isSameAs(sharedQwen);
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER)).isSameAs(sharedQwen);
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.VISUAL))
                .isEqualTo("qwen3.7-plus");
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.ANSWER))
                .isEqualTo("qwen3.7-plus");
        assertThat(configuration.snapshot("service-user").providers())
                .filteredOn(provider -> provider.id().equals("qwen"))
                .extracting(RuntimeModelConfiguration.ProviderView::model)
                .containsExactly("qwen3.7-plus");
        assertThat(configuration.snapshot("service-user").recommendationModel())
                .isEqualTo(new RuntimeModelConfiguration.EffectiveModel("qwen", "qwen3.8-flash"));
    }

    @Test
    void durablePlatformAndPersonalProviderModelsTakePriorityOverTheStartupRecommendationOverride() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel startupQwen = mock(ChatModel.class);
        ChatModel recommendationQwen = mock(ChatModel.class);
        ChatModel platformQwen = mock(ChatModel.class);
        ChatModel personalQwen = mock(ChatModel.class);
        Provider disabled = new Provider(false, "", "", "", false);
        Provider qwen = new Provider(
                true,
                "startup-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                true);
        ModelConfigurationStore store = mock(ModelConfigurationStore.class);
        ModelCredentialCipher cipher = mock(ModelCredentialCipher.class);
        var platformSecret = new ModelCredentialCipher.EncryptedSecret(new byte[16], new byte[12], (short) 1);
        var personalSecret = new ModelCredentialCipher.EncryptedSecret(new byte[16], new byte[12], (short) 1);
        var platformProvider = new ModelConfigurationStore.StoredProvider(
                "qwen",
                platformSecret,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-platform",
                true,
                3);
        var personalProvider = new ModelConfigurationStore.StoredProvider(
                "qwen",
                personalSecret,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-personal",
                true,
                5);
        when(cipher.available()).thenReturn(true);
        when(cipher.decrypt("PLATFORM|qwen", platformSecret)).thenReturn("platform-secret");
        when(cipher.decrypt("PERSONAL|alice|qwen", personalSecret)).thenReturn("personal-secret");
        when(store.platform()).thenReturn(Optional.of(
                new ModelConfigurationStore.StoredConfiguration(List.of(platformProvider), null, 3)));
        when(store.personal("platform-user")).thenReturn(Optional.empty());
        when(store.personal("alice")).thenReturn(Optional.of(
                new ModelConfigurationStore.StoredConfiguration(List.of(personalProvider), null, 5)));
        when(factory.create(
                        "qwen",
                        "startup-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus"))
                .thenReturn(startupQwen);
        when(factory.create(
                        "qwen",
                        "startup-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.8-flash"))
                .thenReturn(recommendationQwen);
        when(factory.create(
                        "qwen",
                        "platform-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen-platform"))
                .thenReturn(platformQwen);
        when(factory.create(
                        "qwen",
                        "personal-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen-personal"))
                .thenReturn(personalQwen);

        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, qwen, disabled),
                "fake", "gemini", "spring-ai", "qwen", "spring-ai", "qwen", "fake", "gemini",
                "spring-ai", "qwen", "qwen3.8-flash", false, "",
                store, cipher, null, 16_000);

        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "platform-user"))
                .isSameAs(platformQwen);
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "platform-user"))
                .isEqualTo("qwen-platform");
        assertThat(configuration.snapshot("platform-user").recommendationModel())
                .isEqualTo(new RuntimeModelConfiguration.EffectiveModel("qwen", "qwen-platform"));
        assertThat(configuration.resolvedModelFor(
                                RuntimeModelConfiguration.Role.RECOMMENDATION, "platform-user"))
                .satisfies(resolved -> {
                    assertThat(resolved.startupDefault()).isFalse();
                    assertThat(resolved.platformManaged()).isTrue();
                });
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .isSameAs(personalQwen);
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .isEqualTo("qwen-personal");
        assertThat(configuration.snapshot("alice").recommendationModel())
                .isEqualTo(new RuntimeModelConfiguration.EffectiveModel("qwen", "qwen-personal"));
        assertThat(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .satisfies(resolved -> {
                    assertThat(resolved.startupDefault()).isFalse();
                    assertThat(resolved.platformManaged()).isFalse();
                });
    }

    @Test
    void rejectsARecommendationModelWithoutAnActiveSpringAiRecommendationProvider() {
        Provider disabled = new Provider(false, "", "", "", false);

        assertThatThrownBy(() -> new RuntimeModelConfiguration(
                        mock(ChatModelFactory.class),
                        new ModelProviderProperties(disabled, disabled, disabled, disabled, disabled),
                        "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                        "fake", "qwen", "qwen3.8-flash", false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a configured Spring AI recommendation provider");
    }

    @Test
    void exposesQwenAsAFirstClassVisionProvider() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel qwenModel = mock(ChatModel.class);
        when(factory.create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3-vl-plus"))
                .thenReturn(qwenModel);
        Provider disabled = new Provider(false, "", "", "", false);
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled, disabled),
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "gemini",
                "fake",
                "qwen",
                false,
                "");

        RuntimeModelConfiguration.Snapshot defaults = configuration.snapshot("player");
        RuntimeModelConfiguration.ProviderView qwen = defaults.providers().stream()
                .filter(provider -> provider.id().equals("qwen"))
                .findFirst()
                .orElseThrow();
        assertThat(qwen.configured()).isFalse();
        assertThat(qwen.visionCapable()).isTrue();
        assertThat(qwen.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(qwen.model()).isEqualTo("qwen3-vl-plus");

        configuration.configure(
                "player",
                "qwen",
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3-vl-plus",
                true);
        RuntimeModelConfiguration.Snapshot assigned =
                configuration.assign("player", "qwen", "qwen", "qwen", "qwen", "qwen");

        assertThat(assigned.assignments().visual()).isEqualTo("qwen");
        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.VISUAL, "player"))
                .isEqualTo("qwen3-vl-plus");
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("player", "", java.util.List.of()));
            assertThat(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL)).isTrue();
            assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL)).isSameAs(qwenModel);
            assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.VISUAL))
                    .isEqualTo("qwen3-vl-plus");
        } finally {
            SecurityContextHolder.clearContext();
        }
        RuntimeModelConfiguration.Snapshot textOnly = configuration.configure(
                "player",
                "qwen",
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3-vl-plus",
                false);
        assertThat(textOnly.assignments().visual()).isEqualTo("fake");
        verify(factory, times(2))
                .create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3-vl-plus");
    }

    @Test
    void rejectsAConfiguredTextOnlyModelFromTheVisualRole() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel qwenModel = mock(ChatModel.class);
        when(factory.create(
                        "qwen", "qwen-secret", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-plus"))
                .thenReturn(qwenModel);
        Provider disabled = new Provider(false, "", "", "", false);
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled, disabled),
                "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                "fake", "qwen", false, "");

        RuntimeModelConfiguration.Snapshot configured = configuration.configure(
                "player",
                "qwen",
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                false);

        assertThat(configured.providers().stream()
                        .filter(provider -> provider.id().equals("qwen"))
                        .findFirst()
                        .orElseThrow()
                        .visionCapable())
                .isFalse();
        assertThatThrownBy(() -> configuration.assign(
                        "player", "qwen", "qwen", "qwen", "qwen", "qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must support page images");
    }

    @Test
    void letsTheConfiguredProviderDecideWhetherAStartupModelNameIsSupported() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel qwenModel = mock(ChatModel.class);
        when(factory.create(
                        "qwen",
                        "qwen-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen-plus"))
                .thenReturn(qwenModel);
        Provider disabled = new Provider(false, "", "", "", false);
        Provider configured = new Provider(
                true,
                "qwen-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus",
                false);

        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, configured, disabled),
                "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                "spring-ai", "qwen", false, "");

        assertThat(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isEqualTo("qwen-plus");
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).isSameAs(qwenModel);
    }

    @Test
    void preservesPersonalModelNamesWithoutMaintainingAStaticModelDenylist() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.create(
                        org.mockito.ArgumentMatchers.eq("qwen"),
                        org.mockito.ArgumentMatchers.eq("qwen-secret"),
                        org.mockito.ArgumentMatchers.eq("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(ChatModel.class));
        Provider disabled = new Provider(false, "", "", "", false);
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, disabled, disabled),
                "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                "fake", "qwen", false, "");

        assertThat(configuration.configure(
                                "player",
                                "qwen",
                                "qwen-secret",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                "QWEN-PLUS-US",
                                true)
                        .providers())
                .anySatisfy(provider -> {
                    assertThat(provider.id()).isEqualTo("qwen");
                    assertThat(provider.model()).isEqualTo("QWEN-PLUS-US");
                });
    }

    @Test
    void namedAccountsNeverInheritStartupCredentialsOrAnotherAccountsClient() {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel startupModel = mock(ChatModel.class);
        ChatModel aliceModel = mock(ChatModel.class);
        Provider disabled = new Provider(false, "", "", "", false);
        Provider startupQwen = new Provider(
                true,
                "startup-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                false);
        when(factory.create(
                        "qwen",
                        "startup-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus"))
                .thenReturn(startupModel);
        when(factory.create(
                        "qwen",
                        "alice-secret",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus"))
                .thenReturn(aliceModel);
        RuntimeModelConfiguration configuration = new RuntimeModelConfiguration(
                factory,
                new ModelProviderProperties(disabled, disabled, disabled, startupQwen, disabled),
                "fake", "gemini", "fake", "gemini", "fake", "gemini", "fake", "gemini",
                "spring-ai", "qwen", false, "service-user");

        assertThat(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isEqualTo("qwen");
        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .isSameAs(startupModel);
        assertThat(configuration.providerFor(
                        RuntimeModelConfiguration.Role.RECOMMENDATION, "service-user"))
                .isEqualTo("qwen");
        assertThat(configuration.snapshot("service-user").managedStartupAccess()).isTrue();
        assertThat(configuration.snapshot("alice").providers())
                .allSatisfy(provider -> {
                    assertThat(provider.configured()).isFalse();
                    assertThat(provider.apiKeyConfigured()).isFalse();
                });
        assertThat(configuration.snapshot("bob").assignments().recommendation()).isEqualTo("fake");
        assertThat(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .isTrue();
        assertThat(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION, "bob"))
                .isTrue();

        configuration.configure(
                "alice",
                "qwen",
                "alice-secret",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                false);
        configuration.assign("alice", "fake", "fake", "fake", "fake", "qwen");

        assertThat(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .isSameAs(aliceModel);
        assertThat(configuration.snapshot("bob").providers())
                .allSatisfy(provider -> assertThat(provider.configured()).isFalse());
        assertThat(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION, "bob"))
                .isTrue();
        assertThatThrownBy(() -> configuration.modelFor(
                        RuntimeModelConfiguration.Role.RECOMMENDATION, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
