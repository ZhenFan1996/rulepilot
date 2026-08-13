package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rulepilot.ingestion.application.BoundedPageImageStoragePipeline;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * Opt-in local measurement over a non-committed rulebook. It uses the storage delay observed in production-like local
 * ingestion to separate pipeline overlap from Poppler variance without requiring MinIO or publishing source content.
 */
class PdfPageImageStoragePipelinePerformanceEvaluationTest {

    @Test
    void measuresBoundedStorageOverlapOnTheSameRealPdf() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");
        Duration pageStorageDelay = Duration.ofMillis(environmentInteger(
                "RULEPILOT_REAL_PDF_STORAGE_DELAY_MS", 90, 1, 5_000));

        // Balance warm filesystem/PDF decoder effects: serial leads the first pair and follows the second pair.
        Measurement serialFirst = measure(rulebook, pageStorageDelay, 1);
        Measurement pipelinedFirst = measure(rulebook, pageStorageDelay, 2);
        Measurement pipelinedSecond = measure(rulebook, pageStorageDelay, 2);
        Measurement serialSecond = measure(rulebook, pageStorageDelay, 1);
        long serialAverageNanos = average(serialFirst.elapsedNanos(), serialSecond.elapsedNanos());
        long pipelinedAverageNanos = average(pipelinedFirst.elapsedNanos(), pipelinedSecond.elapsedNanos());
        double improvement = 1 - pipelinedAverageNanos / (double) serialAverageNanos;

        System.out.printf(
                "PDF storage pipeline measurement: pages=%d, delayMs=%d, serialMs=%d/%d (avg=%d), "
                        + "pipelinedMs=%d/%d (avg=%d), improvement=%.1f%%%n",
                serialFirst.pageCount(),
                pageStorageDelay.toMillis(),
                milliseconds(serialFirst.elapsedNanos()),
                milliseconds(serialSecond.elapsedNanos()),
                milliseconds(serialAverageNanos),
                milliseconds(pipelinedFirst.elapsedNanos()),
                milliseconds(pipelinedSecond.elapsedNanos()),
                milliseconds(pipelinedAverageNanos),
                improvement * 100);
        assertThat(pipelinedFirst.pageCount()).isEqualTo(serialFirst.pageCount());
        assertThat(pipelinedSecond.pageCount()).isEqualTo(serialFirst.pageCount());
        assertThat(serialSecond.pageCount()).isEqualTo(serialFirst.pageCount());
        assertThat(pipelinedAverageNanos).isLessThan(serialAverageNanos);
    }

    private Measurement measure(Path rulebook, Duration pageStorageDelay, int parallelism) throws IOException {
        ExecutorService storageLane = Executors.newFixedThreadPool(parallelism);
        try {
            var pipeline = new BoundedPageImageStoragePipeline(storageLane, parallelism);
            var storedPages = new AtomicInteger();
            var batch = pipeline.openBatch(image -> {
                LockSupport.parkNanos(pageStorageDelay.toNanos());
                storedPages.incrementAndGet();
            });
            long startedAt = System.nanoTime();
            try (InputStream input = Files.newInputStream(rulebook)) {
                new PdfBoxRulebookPreparation(500, 5_000_000, 4, "poppler")
                        .prepare(input, ignored -> {}, batch::submit);
            }
            batch.awaitCompletion();
            return new Measurement(storedPages.get(), System.nanoTime() - startedAt);
        } finally {
            storageLane.shutdownNow();
        }
    }

    private boolean popplerAvailable() {
        try {
            return new ProcessBuilder("pdftoppm", "-v").start().waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private int environmentInteger(String name, int defaultValue, int minimum, int maximum) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return parsed;
    }

    private long average(long first, long second) {
        return first / 2 + second / 2 + (first % 2 + second % 2) / 2;
    }

    private long milliseconds(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private record Measurement(int pageCount, long elapsedNanos) {}
}
