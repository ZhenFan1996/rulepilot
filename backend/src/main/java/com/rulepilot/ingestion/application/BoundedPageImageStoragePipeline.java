package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.RetryableDocumentProcessingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Overlaps rendered-page production with durable image writes without allowing an entire rulebook's JPEGs to queue in
 * memory. A batch admits at most {@code maxInFlight} images and applies backpressure to the rendering thread after that
 * bound is reached.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class BoundedPageImageStoragePipeline {

    private final Executor executor;
    private final int maxInFlight;

    public BoundedPageImageStoragePipeline(
            @Qualifier("documentPageImageStorageExecutor") Executor executor,
            @Value("${rulepilot.document.page-image-storage-parallelism:2}") int maxInFlight) {
        if (maxInFlight < 1 || maxInFlight > 4) {
            throw new IllegalArgumentException("page image storage parallelism must be between one and four");
        }
        this.executor = executor;
        this.maxInFlight = maxInFlight;
    }

    public Batch openBatch(Consumer<RenderedPageImage> durableStore) {
        if (durableStore == null) {
            throw new IllegalArgumentException("a durable page image store is required");
        }
        return new Batch(durableStore);
    }

    public final class Batch {

        private final Consumer<RenderedPageImage> durableStore;
        private final List<CompletableFuture<Void>> pending = new ArrayList<>(maxInFlight);
        private RuntimeException failure;
        private boolean accepting = true;

        private Batch(Consumer<RenderedPageImage> durableStore) {
            this.durableStore = durableStore;
        }

        /** Called by the single PDF rendering thread. */
        public void submit(RenderedPageImage image) {
            if (!accepting) {
                throw new IllegalStateException("page image storage batch is already complete");
            }
            if (image == null) {
                throw new IllegalArgumentException("a rendered page image is required");
            }
            while (pending.size() >= maxInFlight) {
                awaitOneOrMore();
                throwFailure();
            }
            try {
                pending.add(CompletableFuture.runAsync(() -> durableStore.accept(image), executor));
            } catch (RejectedExecutionException rejected) {
                throw new RetryableDocumentProcessingException("page image storage lane is unavailable", rejected);
            }
            collectCompleted();
            throwFailure();
        }

        /** Waits until every accepted image has either been stored or failed, then preserves the original failure. */
        public void awaitCompletion() {
            accepting = false;
            while (!pending.isEmpty()) {
                awaitOneOrMore();
            }
            throwFailure();
        }

        private void awaitOneOrMore() {
            try {
                CompletableFuture.anyOf(pending.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException ignored) {
                // collectCompleted unwraps the exact storage exception below.
            }
            collectCompleted();
        }

        private void collectCompleted() {
            Iterator<CompletableFuture<Void>> iterator = pending.iterator();
            while (iterator.hasNext()) {
                CompletableFuture<Void> completed = iterator.next();
                if (!completed.isDone()) {
                    continue;
                }
                iterator.remove();
                try {
                    completed.join();
                } catch (CompletionException wrapped) {
                    rememberFailure(unwrap(wrapped));
                }
            }
        }

        private Throwable unwrap(CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            while (cause instanceof CompletionException nested && nested.getCause() != null) {
                cause = nested.getCause();
            }
            return cause == null ? wrapped : cause;
        }

        private void rememberFailure(Throwable cause) {
            if (failure != null) {
                if (cause != failure) {
                    failure.addSuppressed(cause);
                }
                return;
            }
            if (cause instanceof RuntimeException runtime) {
                failure = runtime;
                return;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            failure = new IllegalStateException("page image storage failed", cause);
        }

        private void throwFailure() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
