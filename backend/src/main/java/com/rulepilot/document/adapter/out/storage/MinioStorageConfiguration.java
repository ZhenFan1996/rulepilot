package com.rulepilot.document.adapter.out.storage;

import com.rulepilot.document.application.MinioStorageProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioStorageProperties.class)
class MinioStorageConfiguration {

    @Bean
    MinioClient minioClient(MinioStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
