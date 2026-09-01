package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryTemplate;
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
            assertThat(apiClient.httpOptions().retryOptions())
                    .get()
                    .satisfies(options -> assertThat(options.attempts()).contains(1));
            assertThat(apiClient.httpClient().callTimeoutMillis()).isEqualTo(1_234);
            assertThat(apiClient.httpClient().retryOnConnectionFailure()).isFalse();
            assertThat(apiClient.httpClient().followRedirects()).isFalse();
            assertThat(apiClient.httpClient().followSslRedirects()).isFalse();

            RetryTemplate retryTemplate = (RetryTemplate) ReflectionTestUtils.getField(model, "retryTemplate");
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> retryTemplate.invoke(() -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("provider failed");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("provider failed");
            assertThat(attempts).hasValue(1);
        } finally {
            model.destroy();
        }
    }

    @Test
    void disablesProviderSdkRetriesSoTheApplicationOwnsEveryModelAttempt() {
        ChatModelFactory factory = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(30));

        OpenAiChatModel model = (OpenAiChatModel) factory.create(
                "qwen",
                "test-api-key",
                "https://provider.example/v1",
                "qwen-test-model");

        assertThat((OpenAiChatOptions) model.getOptions()).satisfies(options -> {
            assertThat(options.getMaxRetries()).isZero();
            assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(options.getBaseUrl()).isEqualTo("https://provider.example/v1");
            assertThat(options.getModel()).isEqualTo("qwen-test-model");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 503})
    void bufferedOpenAiCompatibleFailurePerformsExactlyOneHttpRequest(int status) throws Exception {
        assertSingleOpenAiCompatibleRequest(status, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 503})
    void streamedOpenAiCompatibleFailurePerformsExactlyOneHttpRequest(int status) throws Exception {
        assertSingleOpenAiCompatibleRequest(status, true);
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

    private void assertSingleOpenAiCompatibleRequest(int status, boolean streaming) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            byte[] error = ("{\"error\":{\"message\":\"test " + status + "\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.start();
        ChatModelFactory factory = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(5));
        OpenAiChatModel model = (OpenAiChatModel) factory.create(
                "compatible",
                "test-api-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model");

        try {
            assertThatThrownBy(() -> {
                        if (streaming) {
                            model.stream(new Prompt("hello")).blockLast();
                        } else {
                            model.call(new Prompt("hello"));
                        }
                    })
                    .isInstanceOf(RuntimeException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
