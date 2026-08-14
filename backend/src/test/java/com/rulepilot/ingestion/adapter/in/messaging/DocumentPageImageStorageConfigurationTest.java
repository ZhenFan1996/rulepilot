package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class DocumentPageImageStorageConfigurationTest {

    private final DocumentPageImageStorageConfiguration configuration =
            new DocumentPageImageStorageConfiguration();

    @Test
    void createsAZeroQueueExecutorAtTheSameBoundAsTheIngestionPipeline() {
        var executor = configuration.documentPageImageStorageExecutor(2);
        try {
            assertThat(executor).isInstanceOfSatisfying(ThreadPoolExecutor.class, pool -> {
                assertThat(pool.getCorePoolSize()).isEqualTo(2);
                assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
                assertThat(pool.getQueue().remainingCapacity()).isZero();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsConfigurationThatWouldRemoveOrExceedTheMemoryBound() {
        assertThatThrownBy(() -> configuration.documentPageImageStorageExecutor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and four");
        assertThatThrownBy(() -> configuration.documentPageImageStorageExecutor(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and four");
    }
}
