package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import org.junit.jupiter.api.Test;

class SpringAiRuleAnswerModelTest {

    @Test
    void selectsConfiguredProviderWithoutCallingExternalApi() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.providerFor(Role.ANSWER)).thenReturn("deepseek");

        SpringAiRuleAnswerModel model = new SpringAiRuleAnswerModel(configuration, new FakeRuleAnswerModel());

        assertThat(model.providerId()).isEqualTo("deepseek");
    }
}
