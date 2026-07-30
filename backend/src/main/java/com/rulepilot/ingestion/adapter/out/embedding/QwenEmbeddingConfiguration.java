package com.rulepilot.ingestion.adapter.out.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.ingestion.EmbeddingProvider;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rulepilot.embedding.provider", havingValue = "qwen")
@EnableConfigurationProperties(QwenEmbeddingProperties.class)
class QwenEmbeddingConfiguration {

    @Bean
    EmbeddingProvider qwenEmbeddingProvider(QwenEmbeddingProperties properties) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException("Qwen embedding requires QWEN_API_KEY");
        }
        long timeoutMillis = properties.requestTimeout().toMillis();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        return new QwenEmbeddingProvider(properties, http, new ObjectMapper());
    }
}
