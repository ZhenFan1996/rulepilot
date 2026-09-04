package com.rulepilot.modelconfig.adapter.out;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatModelFactory {

    private final ObservationRegistry observations;
    private final Duration requestTimeout;
    private final int requestTimeoutMillis;

    public ChatModelFactory(
            ObservationRegistry observations,
            @Value("${rulepilot.models.request-timeout:PT2M}") Duration requestTimeout) {
        this.observations = observations;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model request timeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        try {
            this.requestTimeoutMillis = Math.toIntExact(requestTimeout.toMillis());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("model request timeout exceeds the provider limit", exception);
        }
        if (requestTimeoutMillis == 0) {
            throw new IllegalArgumentException("model request timeout must be at least one millisecond");
        }
    }

    public ChatModel create(String provider, String apiKey, String baseUrl, String model) {
        return switch (provider) {
            case "gemini" -> gemini(apiKey, model);
            case "openai", "deepseek", "qwen", "compatible" -> openAi(apiKey, baseUrl, model);
            default -> throw new IllegalArgumentException("unsupported model provider: " + provider);
        };
    }

    private ChatModel gemini(String apiKey, String model) {
        okhttp3.OkHttpClient transport = new okhttp3.OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(requestTimeout)
                .readTimeout(requestTimeout)
                .writeTimeout(requestTimeout)
                .callTimeout(requestTimeout)
                .build();
        Client client = Client.builder()
                .apiKey(required(apiKey, "Gemini API key"))
                .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
                .httpOptions(HttpOptions.builder()
                        .timeout(requestTimeoutMillis)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder()
                        .model(required(model, "Gemini model"))
                        .build())
                .toolCallingManager(ToolCallingManager.builder()
                        .observationRegistry(observations)
                        .build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .observationRegistry(observations)
                .build();
    }

    private ChatModel openAi(String apiKey, String baseUrl, String model) {
        String resolvedApiKey = required(apiKey, "model API key");
        String resolvedBaseUrl = required(baseUrl, "model base URL");
        String resolvedModel = required(model, "model name");
        com.openai.core.ClientOptions syncOptions = openAiClientOptions(resolvedApiKey, resolvedBaseUrl);
        com.openai.core.ClientOptions asyncOptions = openAiClientOptions(resolvedApiKey, resolvedBaseUrl);
        OpenAIClientAsyncImpl asyncClient = new OpenAIClientAsyncImpl(asyncOptions);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(resolvedApiKey)
                        .baseUrl(resolvedBaseUrl)
                        .model(resolvedModel)
                        .timeout(requestTimeout)
                        .maxRetries(0)
                        .build())
                .openAiClient(new OpenAIClientImpl(syncOptions))
                .openAiClientAsync(asyncClient)
                .observationRegistry(observations)
                .build();
        return new IncrementalOpenAiChatModel(chatModel, asyncClient);
    }

    private com.openai.core.ClientOptions openAiClientOptions(String apiKey, String baseUrl) {
        return com.openai.core.ClientOptions.builder()
                .httpClient(new SingleAttemptOpenAiHttpClient(requestTimeout, observations))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(requestTimeout)
                .maxRetries(0)
                .putHeader("User-Agent", "spring-ai-openai")
                .build();
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
