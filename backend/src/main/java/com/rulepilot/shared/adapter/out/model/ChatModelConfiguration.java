package com.rulepilot.shared.adapter.out.model;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryTemplate;

@Configuration(proxyBeanMethods = false)
public class ChatModelConfiguration {

    @Bean("openai")
    @ConditionalOnProperty(name = "rulepilot.models.openai.enabled", havingValue = "true")
    ChatModel openAiChatModel(
            @Value("${rulepilot.models.openai.api-key}") String apiKey,
            @Value("${rulepilot.models.openai.base-url}") String baseUrl,
            @Value("${rulepilot.models.openai.model}") String model,
            ObservationRegistry observations) {
        return openAi(apiKey, baseUrl, model, observations);
    }

    @Bean("gemini")
    @ConditionalOnProperty(name = "rulepilot.models.gemini.enabled", havingValue = "true")
    ChatModel geminiChatModel(
            @Value("${rulepilot.models.gemini.api-key}") String apiKey,
            @Value("${rulepilot.models.gemini.model}") String model,
            ObservationRegistry observations) {
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

    @Bean("deepseek")
    @ConditionalOnProperty(name = "rulepilot.models.deepseek.enabled", havingValue = "true")
    ChatModel deepSeekChatModel(
            @Value("${rulepilot.models.deepseek.api-key}") String apiKey,
            @Value("${rulepilot.models.deepseek.base-url}") String baseUrl,
            @Value("${rulepilot.models.deepseek.model}") String model,
            ObservationRegistry observations) {
        return openAi(apiKey, baseUrl, model, observations);
    }

    @Bean("compatible")
    @ConditionalOnProperty(name = "rulepilot.models.compatible.enabled", havingValue = "true")
    ChatModel compatibleChatModel(
            @Value("${rulepilot.models.compatible.api-key}") String apiKey,
            @Value("${rulepilot.models.compatible.base-url}") String baseUrl,
            @Value("${rulepilot.models.compatible.model}") String model,
            ObservationRegistry observations) {
        return openAi(apiKey, baseUrl, model, observations);
    }

    private ChatModel openAi(String apiKey, String baseUrl, String model, ObservationRegistry observations) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(required(apiKey, "model API key"))
                        .baseUrl(required(baseUrl, "model base URL"))
                        .model(required(model, "model name"))
                        .build())
                .observationRegistry(observations)
                .build();
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " is required when its provider is enabled");
        }
        return value.trim();
    }
}
