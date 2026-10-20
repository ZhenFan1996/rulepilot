package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/** Bounds optional visual selection so a slow provider cannot hold a cited chapter indefinitely. */
@Component("boundedVisualRegionLocator")
@Profile("!test")
public class BoundedVisualRegionLocator implements VisualRegionLocator {

    private static final Logger log = LoggerFactory.getLogger(BoundedVisualRegionLocator.class);

    private final VisualRegionLocator delegate;
    private final AsyncTaskExecutor executor;
    private final Duration timeout;

    public BoundedVisualRegionLocator(
            @Qualifier("springAiVisualRegionLocator") VisualRegionLocator delegate,
            @Qualifier("visualLocationExecutor") AsyncTaskExecutor executor,
            @Value("${rulepilot.visual.location-timeout:PT45S}") Duration timeout) {
        this.delegate = delegate;
        this.executor = executor;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("visual location timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return delegate.supportsVisualEvidence(modelConfigurationOwner);
    }

    @Override
    public List<VisualFocus> observe(Request request) {
        return observe(request, timeout);
    }

    @Override
    public List<VisualFocus> observe(Request request, Duration remainingWorkflowTime) {
        if (remainingWorkflowTime == null || remainingWorkflowTime.isZero() || remainingWorkflowTime.isNegative()) {
            return List.of();
        }
        Duration effectiveTimeout = remainingWorkflowTime.compareTo(timeout) < 0
                ? remainingWorkflowTime
                : timeout;
        Future<List<VisualFocus>> work;
        try {
            work = executor.submit(() -> delegate.observe(request));
        } catch (RejectedExecutionException busy) {
            log.info("Skipped visual enrichment because another visual request is still running");
            return List.of();
        }
        try {
            return work.get(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowProvider) {
            work.cancel(true);
            log.info("Skipped visual enrichment after {} ms", effectiveTimeout.toMillis());
            return List.of();
        } catch (InterruptedException interrupted) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentExecutionStoppedException stopped) throw stopped;
            log.info("Skipped visual enrichment after provider failure: {}", rootMessage(failed));
            return List.of();
        }
    }

    private String rootMessage(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause == null) return "unknown";
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
