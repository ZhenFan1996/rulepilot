package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OfficialRulebookImportConfigurationTest {

    @Test
    void startsTwoOrdinaryImportsWithoutWaitingForTheQueueToFill() throws InterruptedException {
        var executor = new OfficialRulebookImportConfiguration().officialRulebookImportExecutor(8);
        executor.initialize();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            Runnable importTask = () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };

            executor.execute(importTask);
            executor.execute(importTask);

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
