package com.rulepilot.modelconfig.adapter.out;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatModelFactory {

    private final ObservationRegistry observations;
    private final Duration requestTimeout;

    public ChatModelFactory(
            ObservationRegistry observations,
            @Value("${rulepilot.models.request-timeout:PT2M}") Duration requestTimeout) {
        this.observations = observations;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model request timeout must be positive");
        }
        this.requestTimeout = requestTimeout;
    }

    public ChatModel create(String provider, String apiKey, String baseUrl, String model) {
        return switch (provider) {
            case "gemini" -> gemini(apiKey, model);
            case "openai", "deepseek", "compatible" -> openAi(apiKey, baseUrl, model);
            default -> throw new IllegalArgumentException("unsupported model provider: " + provider);
        };
    }

    private ChatModel gemini(String apiKey, String model) {
        Client client = Client.builder().apiKey(required(apiKey, "Gemini API key")).build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder()
                        .model(required(model, "Gemini model"))
                        .build())
                .toolCallingManager(ToolCallingManager.builder()
                        .observationRegistry(observations)
                        .build())
                .retryTemplate(new RetryTemplate())
                .observationRegistry(observations)
                .build();
    }

    private ChatModel openAi(String apiKey, String baseUrl, String model) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(required(apiKey, "model API key"))
                        .baseUrl(required(baseUrl, "model base URL"))
                        .model(required(model, "model name"))
                        .build())
                .httpClientBuilderCustomizer(builder -> builder.timeout(requestTimeout))
                .observationRegistry(observations)
                .build();
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
