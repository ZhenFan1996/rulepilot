package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
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

    @Test
    void usesTheRemainingWorkflowTimeWhenItIsShorterThanThePerCallLimit() {
        var executor = executor();
        try {
            var bounded = new BoundedVisualRegionLocator(ignored -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }, executor, Duration.ofSeconds(1));

            long started = System.nanoTime();
            var result = bounded.locateGuideWithResult(
                    request(UUID.randomUUID()),
                    Duration.ofMillis(25));

            assertThat(result.diagnostic()).isEqualTo(VisualRegionLocator.Diagnostic.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void preservesRunCancellationInsteadOfRelabelingItAsAProviderFailure() {
        var executor = executor();
        try {
            VisualRegionLocator stopped = ignored -> {
                throw new AgentExecutionStoppedException(AgentExecutionStoppedException.StopReason.CANCELLED);
            };
            var bounded = new BoundedVisualRegionLocator(stopped, executor, Duration.ofSeconds(1));

            assertThatThrownBy(() -> bounded.locateGuideWithResult(request(UUID.randomUUID())))
                    .isInstanceOf(AgentExecutionStoppedException.class)
                    .hasMessageContaining("CANCELLED");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void preservesTheOwnersVisualCapabilityBoundary() {
        VisualRegionLocator textOnly = new VisualRegionLocator() {
            @Override
            public Optional<LocatedRegion> locate(VisualLocationRequest request) {
                return Optional.empty();
            }

            @Override
            public boolean supportsVisualEvidence(String modelConfigurationOwner) {
                return false;
            }
        };
        var executor = executor();
        try {
            var bounded = new BoundedVisualRegionLocator(textOnly, executor, Duration.ofSeconds(1));

            assertThat(bounded.supportsVisualEvidence("text-only-player")).isFalse();
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
                List.of(new Candidate(
                        "candidate_1",
                        1,
                        new RulebookUnderstanding.Rectangle(100, 100, 200, 200),
                        VisualSourceKind.PAGE_REGION)),
                List.of(new VisualRegionLocator.PageImage(1, "image/png", new byte[] {1})));
    }
}
