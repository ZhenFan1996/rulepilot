package com.rulepilot.document.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DocumentOutboxWakeupConfigurationTest {

    @Test
    void usesOneBoundedLaneForPromptOutboxPublication() throws InterruptedException {
        var executor = new DocumentOutboxWakeupConfiguration().documentOutboxWakeupExecutor(1);
        var completed = new CountDownLatch(1);
        executor.initialize();

        try {
            executor.execute(completed::countDown);

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("document-outbox-wakeup-");
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsAnUnboundedWakeupQueueConfiguration() {
        var configuration = new DocumentOutboxWakeupConfiguration();

        assertThatThrownBy(() -> configuration.documentOutboxWakeupExecutor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.documentOutboxWakeupExecutor(41))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAThirdWakeupWhenTheSingleWorkerAndBoundedQueueAreFull() throws InterruptedException {
        var executor = new DocumentOutboxWakeupConfiguration().documentOutboxWakeupExecutor(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.initialize();

        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {});

            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
