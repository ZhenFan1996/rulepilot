package com.rulepilot.document.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.storage.minio")
public record MinioStorageProperties(
        String endpoint, String accessKey, String secretKey, String bucket, long maxPdfBytes) {

    public MinioStorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MinIO endpoint is required");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("MinIO credentials are required");
        }
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("MinIO bucket name is invalid");
        }
        if (maxPdfBytes <= 0) {
            throw new IllegalArgumentException("maximum PDF size must be positive");
        }
    }
}
