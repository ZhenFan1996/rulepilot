package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DocumentPageImageStorageConfigurationTest {

    private final DocumentPageImageStorageConfiguration configuration =
            new DocumentPageImageStorageConfiguration();

    @Test
    void createsAZeroQueueExecutorAtTheSameBoundAsTheIngestionPipeline() throws Exception {
        var executor = configuration.documentPageImageStorageExecutor(2);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var thirdAccepted = new CountDownLatch(1);
        var third = new AtomicReference<java.util.concurrent.Future<?>>();
        try {
            executor.submit(() -> awaitRelease(entered, release));
            executor.submit(() -> awaitRelease(entered, release));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Thread submitter = Thread.ofVirtual().start(() -> {
                third.set(executor.submit(() -> {}));
                thirdAccepted.countDown();
            });
            assertThat(thirdAccepted.await(150, TimeUnit.MILLISECONDS))
                    .as("a third image must not enter a hidden queue while both bounded lanes are occupied")
                    .isFalse();

            release.countDown();
            assertThat(thirdAccepted.await(1, TimeUnit.SECONDS)).isTrue();
            submitter.join();
            third.get().get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
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

    private void awaitRelease(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
