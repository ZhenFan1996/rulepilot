package com.rulepilot.visualaid.adapter.out.docling;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.visual-aid.docling")
public record DoclingVisualLayoutProperties(
        String serviceUrl,
        String apiKey,
        Duration timeout,
        Duration pollInterval,
        long maxFileBytes,
        int maxResultBytes) {

    public DoclingVisualLayoutProperties {
        serviceUrl = serviceUrl == null ? "" : trimTrailingSlash(serviceUrl.strip());
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        if (timeout.isZero()
                || timeout.isNegative()
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || pollInterval.compareTo(timeout) >= 0
                || maxFileBytes < 1
                || maxResultBytes < 1) {
            throw new IllegalArgumentException("Docling visual layout configuration is invalid");
        }
        if (!serviceUrl.isBlank()) {
            URI endpoint = URI.create(serviceUrl);
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
                throw new IllegalArgumentException("Docling visual layout service URL must be HTTPS");
            }
        }
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
