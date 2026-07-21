package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BoundedVisualRegionLocatorTest {

    @Test
    void returns_the_delegate_result_within_its_budget() {
        UUID evidence = UUID.randomUUID();
        var executor = executor();
        try {
            var bounded = new BoundedVisualRegionLocator(
                    ignored -> Optional.of(new VisualRegionLocator.LocatedRegion(
                            1, "setup", 100, 100, 200, 200, List.of(evidence))), executor, Duration.ofSeconds(1));

            assertThat(bounded.locate(request(evidence))).isPresent();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void abandons_a_slow_optional_visual_request() {
        var executor = executor();
        try {
            var bounded = new BoundedVisualRegionLocator(ignored -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }, executor, Duration.ofMillis(25));

            long started = System.nanoTime();
            assertThat(bounded.locate(request(UUID.randomUUID()))).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
        } finally {
            executor.shutdown();
        }
    }

    private ThreadPoolTaskExecutor executor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    private VisualRegionLocator.VisualLocationRequest request(UUID evidence) {
        return new VisualRegionLocator.VisualLocationRequest(
                "开局设置",
                List.of(new VisualRegionLocator.Claim(evidence, "把棋子放到起始位置。")),
                List.of(new Candidate(1, new RulebookUnderstanding.Rectangle(100, 100, 200, 200), "起始位置")),
                List.of(new VisualRegionLocator.PageImage(1, "image/png", new byte[] {1})));
    }
}
