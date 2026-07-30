package com.rulepilot.ingestion.adapter.out.embedding;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.embedding.qwen")
public record QwenEmbeddingProperties(
        String apiKey,
        String baseUrl,
        String model,
        int dimensions,
        Duration requestTimeout,
        int batchSize) {

    private static final Set<Integer> SUPPORTED_DIMENSIONS =
            Set.of(64, 128, 256, 512, 768, 1024, 1536, 2048);

    public QwenEmbeddingProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "" : trimTrailingSlash(baseUrl.strip());
        model = model == null ? "" : model.strip();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        if (baseUrl.isBlank() || model.isBlank() || !SUPPORTED_DIMENSIONS.contains(dimensions)
                || requestTimeout.isZero() || requestTimeout.isNegative()
                || batchSize < 1 || batchSize > 10) {
            throw new IllegalArgumentException("Qwen embedding configuration is invalid");
        }
        URI endpoint = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Qwen embedding base URL must be HTTPS");
        }
    }

    public String providerId() {
        return "qwen:" + model + ":" + dimensions;
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
