package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.VisualRegionLocator.VisualLocationRequest;
import java.time.Duration;
import java.util.Optional;
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

/**
 * Bounds optional visual enrichment so a slow vision provider can never delay an already published text lesson.
 */
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
    public Optional<LocatedRegion> locate(VisualLocationRequest request) {
        Future<Optional<LocatedRegion>> work;
        try {
            work = executor.submit(() -> delegate.locate(request));
        } catch (RejectedExecutionException busy) {
            log.info("Skipped visual enrichment because another visual request is still running");
            return Optional.empty();
        }
        try {
            return work.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slowProvider) {
            work.cancel(true);
            log.info("Skipped visual enrichment after {} ms", timeout.toMillis());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException failed) {
            log.info("Skipped visual enrichment after provider failure: {}", rootMessage(failed));
            return Optional.empty();
        }
    }

    private String rootMessage(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause == null) return "unknown";
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
