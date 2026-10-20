package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DocumentPageReadPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void releasesTheMetadataConnectionBeforeReadingObjectStorage() {
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(POSTGRES.getJdbcUrl()); pool.setUsername(POSTGRES.getUsername()); pool.setPassword(POSTGRES.getPassword());
            pool.setMaximumPoolSize(1); pool.setConnectionTimeout(250);
            var jdbc = new JdbcTemplate(pool);
            var repository = mock(RuleDocumentRepository.class);
            var storage = mock(DocumentStorage.class);
            UUID version = UUID.randomUUID();
            when(repository.findVersion(version)).thenAnswer(call -> {
                jdbc.queryForObject("select 1", Integer.class);
                return Optional.of(new DocumentVersion(version, UUID.randomUUID(), 1, "source.pdf", "source", "a".repeat(64),
                        1, "application/pdf", ProcessingStatus.READY, Instant.now()));
            });
            when(repository.findPageImages(version, Set.of(1))).thenAnswer(call -> {
                jdbc.queryForObject("select 1", Integer.class);
                return List.of(new RuleDocumentRepository.PageImageMetadata(1, "page", 100, 200));
            });
            when(storage.open("page")).thenAnswer(call -> {
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                // Even with a single-connection pool, unrelated database work can proceed during an image read.
                assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
                return new ByteArrayInputStream(new byte[]{1, 2, 3});
            });
            var transaction = new TransactionInterceptor();
            transaction.setTransactionManager(new DataSourceTransactionManager(pool));
            transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            var proxy = new ProxyFactory(new DocumentProcessingService(repository, storage));
            proxy.addAdvice(transaction);
            var pages = (DocumentPageImages) proxy.getProxy();
            assertThat(pages.read(version, Set.of(1))).singleElement().satisfies(page -> assertThat(page.content()).containsExactly(1, 2, 3));
        }
    }
}
