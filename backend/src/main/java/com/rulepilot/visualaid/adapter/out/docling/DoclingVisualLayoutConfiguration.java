package com.rulepilot.visualaid.adapter.out.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.visualaid.application.VisualLayoutExtractor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DoclingVisualLayoutProperties.class)
class DoclingVisualLayoutConfiguration {

    @Bean
    @ConditionalOnProperty(name = "rulepilot.visual-aid.docling.enabled", havingValue = "true")
    VisualLayoutExtractor doclingVisualLayoutExtractor(DoclingVisualLayoutProperties properties) {
        if (properties.serviceUrl().isBlank() || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Enabled Docling visual layout requires a service URL and API key");
        }
        long timeoutMillis = properties.timeout().toMillis();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutMillis, 20_000), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        return new DoclingVisualLayoutExtractor(properties, http, new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(VisualLayoutExtractor.class)
    VisualLayoutExtractor unavailableVisualLayoutExtractor() {
        return VisualLayoutExtractor.unavailable();
    }
}
