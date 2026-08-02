package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

class ChatModelFactoryTest {

    @Test
    void appliesTheSharedRequestTimeoutToGeminiHttpCalls() throws Exception {
        ChatModelFactory factory = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofMillis(1_234));
        GoogleGenAiChatModel model = (GoogleGenAiChatModel) factory.create(
                "gemini", "test-api-key", null, "gemini-test-model");

        try {
            Client client = (Client) ReflectionTestUtils.getField(model, "genAiClient");
            ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");

            assertThat(apiClient.httpOptions().timeout()).contains(1_234);
            assertThat(apiClient.httpClient().callTimeoutMillis()).isEqualTo(1_234);
        } finally {
            model.destroy();
        }
    }

    @Test
    void rejectsPositiveDurationsThatTheGeminiClientWouldTreatAsNoTimeout() {
        assertThatThrownBy(() -> new ChatModelFactory(
                        ObservationRegistry.NOOP, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one millisecond");
    }

    @Test
    void rejectsDurationsOutsideTheGeminiClientIntegerRange() {
        assertThatThrownBy(() -> new ChatModelFactory(
                        ObservationRegistry.NOOP, Duration.ofMillis((long) Integer.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider limit");
    }
}
