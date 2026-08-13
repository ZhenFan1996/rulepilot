package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.RetryableDocumentProcessingException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedPageImageStoragePipelineTest {

    @Test
    void overlapsStorageButAppliesBackpressureAtTheConfiguredImageBound() throws Exception {
        ExecutorService storageLane = Executors.newFixedThreadPool(2);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            var pipeline = new BoundedPageImageStoragePipeline(storageLane, 2);
            var firstTwoStarted = new CountDownLatch(2);
            var releaseFirstTwo = new CountDownLatch(1);
            var active = new AtomicInteger();
            var peakActive = new AtomicInteger();
            Set<Integer> storedPages = ConcurrentHashMap.newKeySet();
            var batch = pipeline.openBatch(image -> {
                int currentActive = active.incrementAndGet();
                peakActive.accumulateAndGet(currentActive, Math::max);
                firstTwoStarted.countDown();
                try {
                    if (image.pageNumber() <= 2 && !releaseFirstTwo.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test storage gate timed out");
                    }
                    storedPages.add(image.pageNumber());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test storage was interrupted", interrupted);
                } finally {
                    active.decrementAndGet();
                }
            });

            batch.submit(image(1));
            batch.submit(image(2));
            assertThat(firstTwoStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var thirdSubmission = producer.submit(() -> batch.submit(image(3)));
            assertThat(thirdSubmission.isDone()).isFalse();
            releaseFirstTwo.countDown();
            thirdSubmission.get(5, TimeUnit.SECONDS);
            batch.awaitCompletion();

            assertThat(storedPages).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(peakActive.get()).isEqualTo(2);
        } finally {
            producer.shutdownNow();
            storageLane.shutdownNow();
        }
    }

    @Test
    void preservesTheOriginalRetryableStorageFailure() {
        RetryableDocumentProcessingException storageFailure =
                new RetryableDocumentProcessingException("storage timeout", new IllegalStateException("offline"));
        var pipeline = new BoundedPageImageStoragePipeline(Runnable::run, 1);
        var batch = pipeline.openBatch(image -> {
            throw storageFailure;
        });

        assertThatThrownBy(() -> batch.submit(image(1))).isSameAs(storageFailure);
        assertThatThrownBy(batch::awaitCompletion).isSameAs(storageFailure);
    }

    @Test
    void classifiesExecutorRejectionAsRetryableInfrastructureFailure() {
        var pipeline = new BoundedPageImageStoragePipeline(task -> {
            throw new RejectedExecutionException("shutting down");
        }, 1);
        var batch = pipeline.openBatch(ignored -> {});

        assertThatThrownBy(() -> batch.submit(image(1)))
                .isInstanceOf(RetryableDocumentProcessingException.class)
                .hasMessage("page image storage lane is unavailable")
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void rejectsUnsafeParallelismAndSubmissionsAfterCompletion() {
        assertThatThrownBy(() -> new BoundedPageImageStoragePipeline(Runnable::run, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and four");
        assertThatThrownBy(() -> new BoundedPageImageStoragePipeline(Runnable::run, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between one and four");

        var batch = new BoundedPageImageStoragePipeline(Runnable::run, 1).openBatch(ignored -> {});
        batch.awaitCompletion();

        assertThatThrownBy(() -> batch.submit(image(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already complete");
    }

    private RenderedPageImage image(int pageNumber) {
        return new RenderedPageImage(pageNumber, new byte[] {(byte) pageNumber}, 100, 100);
    }
}
