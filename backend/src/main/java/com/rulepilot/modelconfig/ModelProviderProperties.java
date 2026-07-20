package com.rulepilot.modelconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.models")
public record ModelProviderProperties(
        Provider openai,
        Provider gemini,
        Provider deepseek,
        Provider qwen,
        Provider compatible) {

    public record Provider(boolean enabled, String apiKey, String baseUrl, String model, boolean visionCapable) {}
}
