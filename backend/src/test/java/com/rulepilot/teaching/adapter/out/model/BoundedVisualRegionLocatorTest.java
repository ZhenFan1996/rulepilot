package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.*;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.shared.PlayerLocale;
import com.rulepilot.teaching.VisualRegionLocator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

class BoundedVisualRegionLocatorTest {
    private final VisualRegionLocator.Request request = new VisualRegionLocator.Request("Setup",
            List.of(new PageImage(1, "image/png", new byte[]{1}, 1, 1)), Map.of(), "player", null, PlayerLocale.EN);

    @Test void aSlowOptionalObservationReturnsWithoutHoldingTheChapterAndCancelsItsWork() throws Exception {
        var interrupted = new CountDownLatch(1);
        VisualRegionLocator provider = ignored -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); interrupted.countDown(); }
            return List.of();
        };
        try (var executor = new SimpleAsyncTaskExecutor()) {
            executor.setVirtualThreads(true);
            var bounded = new BoundedVisualRegionLocator(provider, executor, Duration.ofSeconds(10));
            assertThat(bounded.observe(request, Duration.ofMillis(100))).isEmpty();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void ownerCancellationIsNotReportedAsAnOptionalImageFailure() {
        try (var executor = new SimpleAsyncTaskExecutor()) {
            executor.setVirtualThreads(true);
            var bounded = new BoundedVisualRegionLocator(ignored -> {
                throw new AgentExecutionStoppedException(AgentExecutionStoppedException.StopReason.CANCELLED);
            }, executor, Duration.ofSeconds(10));
            assertThatThrownBy(() -> bounded.observe(request)).isInstanceOf(AgentExecutionStoppedException.class);
        }
    }
}
