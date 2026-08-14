package com.rulepilot.ingestion.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rulepilot.ingestion.application.BoundedPageImageStoragePipeline;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
        Measurement serialFirst = measure(rulebook, pageStorageDelay, 1, 4);
        Measurement pipelinedFirst = measure(rulebook, pageStorageDelay, 2, 4);
        Measurement pipelinedSecond = measure(rulebook, pageStorageDelay, 2, 4);
        Measurement serialSecond = measure(rulebook, pageStorageDelay, 1, 4);
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

    @Test
    void comparesPopplerEmissionBatchesWithTheBoundedStorageLane() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_POPPLER_BATCH_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");
        Duration pageStorageDelay = Duration.ofMillis(environmentInteger(
                "RULEPILOT_REAL_PDF_STORAGE_DELAY_MS", 90, 1, 5_000));

        // Balance warm filesystem/PDF decoder effects while comparing only Poppler's page-emission group size.
        Measurement fourPageFirst = measure(rulebook, pageStorageDelay, 2, 4);
        Measurement twoPageFirst = measure(rulebook, pageStorageDelay, 2, 2);
        Measurement twoPageSecond = measure(rulebook, pageStorageDelay, 2, 2);
        Measurement fourPageSecond = measure(rulebook, pageStorageDelay, 2, 4);
        long fourPageAverageNanos = average(fourPageFirst.elapsedNanos(), fourPageSecond.elapsedNanos());
        long twoPageAverageNanos = average(twoPageFirst.elapsedNanos(), twoPageSecond.elapsedNanos());
        double improvement = 1 - twoPageAverageNanos / (double) fourPageAverageNanos;

        System.out.printf(
                "PDF Poppler batch measurement: pages=%d, delayMs=%d, batch4Ms=%d/%d (avg=%d), "
                        + "batch2Ms=%d/%d (avg=%d), improvement=%.1f%%%n",
                fourPageFirst.pageCount(),
                pageStorageDelay.toMillis(),
                milliseconds(fourPageFirst.elapsedNanos()),
                milliseconds(fourPageSecond.elapsedNanos()),
                milliseconds(fourPageAverageNanos),
                milliseconds(twoPageFirst.elapsedNanos()),
                milliseconds(twoPageSecond.elapsedNanos()),
                milliseconds(twoPageAverageNanos),
                improvement * 100);
        assertThat(twoPageFirst.pageCount()).isEqualTo(fourPageFirst.pageCount());
        assertThat(twoPageSecond.pageCount()).isEqualTo(fourPageFirst.pageCount());
        assertThat(fourPageSecond.pageCount()).isEqualTo(fourPageFirst.pageCount());
    }

    @Test
    void provesPopplerEmissionBatchingDoesNotChangeRenderedEvidence() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_POPPLER_BATCH_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");

        List<RenderedDigest> fourPage = renderDigests(rulebook, 4);
        List<RenderedDigest> twoPage = renderDigests(rulebook, 2);

        assertThat(twoPage).containsExactlyElementsOf(fourPage);
    }

    @Test
    void comparesSinglePageProcessOverheadWithTheTwoPageCandidate() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_POPPLER_SINGLE_PAGE_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");
        Duration pageStorageDelay = Duration.ofMillis(environmentInteger(
                "RULEPILOT_REAL_PDF_STORAGE_DELAY_MS", 90, 1, 5_000));

        Measurement twoPageFirst = measure(rulebook, pageStorageDelay, 2, 2);
        Measurement onePageFirst = measure(rulebook, pageStorageDelay, 2, 1);
        Measurement onePageSecond = measure(rulebook, pageStorageDelay, 2, 1);
        Measurement twoPageSecond = measure(rulebook, pageStorageDelay, 2, 2);
        long twoPageAverageNanos = average(twoPageFirst.elapsedNanos(), twoPageSecond.elapsedNanos());
        long onePageAverageNanos = average(onePageFirst.elapsedNanos(), onePageSecond.elapsedNanos());

        System.out.printf(
                "PDF Poppler single-page measurement: pages=%d, delayMs=%d, batch2Ms=%d/%d (avg=%d), "
                        + "batch1Ms=%d/%d (avg=%d), batch1Delta=%.1f%%%n",
                twoPageFirst.pageCount(),
                pageStorageDelay.toMillis(),
                milliseconds(twoPageFirst.elapsedNanos()),
                milliseconds(twoPageSecond.elapsedNanos()),
                milliseconds(twoPageAverageNanos),
                milliseconds(onePageFirst.elapsedNanos()),
                milliseconds(onePageSecond.elapsedNanos()),
                milliseconds(onePageAverageNanos),
                (onePageAverageNanos / (double) twoPageAverageNanos - 1) * 100);
        assertThat(onePageFirst.pageCount()).isEqualTo(twoPageFirst.pageCount());
        assertThat(onePageSecond.pageCount()).isEqualTo(twoPageFirst.pageCount());
        assertThat(twoPageSecond.pageCount()).isEqualTo(twoPageFirst.pageCount());
    }

    @Test
    void comparesStreamedBoundedSessionsWithTheTwoPageBaseline() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_POPPLER_STREAM_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");
        Duration pageStorageDelay = Duration.ofMillis(environmentInteger(
                "RULEPILOT_REAL_PDF_STORAGE_DELAY_MS", 90, 1, 5_000));

        Measurement twoPageFirst = measureLegacyBatches(rulebook, pageStorageDelay, 2, 2);
        Measurement streamedFirst = measureWithMode(rulebook, pageStorageDelay, 2, 8, true);
        Measurement streamedSecond = measureWithMode(rulebook, pageStorageDelay, 2, 8, true);
        Measurement twoPageSecond = measureLegacyBatches(rulebook, pageStorageDelay, 2, 2);
        long twoPageAverageNanos = average(twoPageFirst.elapsedNanos(), twoPageSecond.elapsedNanos());
        long streamedAverageNanos = average(streamedFirst.elapsedNanos(), streamedSecond.elapsedNanos());

        System.out.printf(
                "PDF Poppler bounded-stream measurement: pages=%d, delayMs=%d, batch2Ms=%d/%d (avg=%d), "
                        + "stream8Ms=%d/%d (avg=%d), improvement=%.1f%%%n",
                streamedFirst.pageCount(),
                pageStorageDelay.toMillis(),
                milliseconds(twoPageFirst.elapsedNanos()),
                milliseconds(twoPageSecond.elapsedNanos()),
                milliseconds(twoPageAverageNanos),
                milliseconds(streamedFirst.elapsedNanos()),
                milliseconds(streamedSecond.elapsedNanos()),
                milliseconds(streamedAverageNanos),
                (1 - streamedAverageNanos / (double) twoPageAverageNanos) * 100);
        assertThat(streamedFirst.pageCount()).isEqualTo(twoPageFirst.pageCount());
        assertThat(streamedSecond.pageCount()).isEqualTo(twoPageFirst.pageCount());
        assertThat(twoPageSecond.pageCount()).isEqualTo(twoPageFirst.pageCount());
    }

    @Test
    void provesStreamedPopplerSessionsMatchTheTwoPageBaseline() throws IOException {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_PDF_POPPLER_STREAM_EVAL")));
        Path rulebook = Path.of(requiredEnvironment("RULEPILOT_REAL_PDF_STORAGE_PIPELINE_PATH"));
        assumeTrue(Files.isRegularFile(rulebook), "configured PDF does not exist");
        assumeTrue(popplerAvailable(), "pdftoppm is required for the production renderer measurement");

        List<RenderedDigest> twoPageBaseline = renderDigests(rulebook, 2, false);
        List<RenderedDigest> eightPageStream = renderDigests(rulebook, 8, true);

        assertThat(eightPageStream).containsExactlyElementsOf(twoPageBaseline);
    }

    private Measurement measure(
            Path rulebook,
            Duration pageStorageDelay,
            int parallelism,
            int renderSessionPages) throws IOException {
        return measureWithMode(rulebook, pageStorageDelay, parallelism, renderSessionPages, true);
    }

    private Measurement measureWithMode(
            Path rulebook,
            Duration pageStorageDelay,
            int parallelism,
            int renderSessionPages,
            boolean streamCompletedPages) throws IOException {
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
                new PdfBoxRulebookPreparation(
                                500, 5_000_000, renderSessionPages, "poppler", streamCompletedPages)
                        .prepare(input, ignored -> {}, batch::submit);
            }
            batch.awaitCompletion();
            return new Measurement(storedPages.get(), System.nanoTime() - startedAt);
        } finally {
            storageLane.shutdownNow();
        }
    }

    private Measurement measureLegacyBatches(
            Path rulebook,
            Duration pageStorageDelay,
            int parallelism,
            int renderSessionPages) throws IOException {
        return measureWithMode(rulebook, pageStorageDelay, parallelism, renderSessionPages, false);
    }

    private List<RenderedDigest> renderDigests(Path rulebook, int renderSessionPages) throws IOException {
        return renderDigests(rulebook, renderSessionPages, true);
    }

    private List<RenderedDigest> renderDigests(
            Path rulebook, int renderSessionPages, boolean streamCompletedPages) throws IOException {
        List<RenderedDigest> rendered = new ArrayList<>();
        try (InputStream input = Files.newInputStream(rulebook)) {
            new PdfBoxRulebookPreparation(
                            500, 5_000_000, renderSessionPages, "poppler", streamCompletedPages)
                    .prepare(input, ignored -> {}, image -> rendered.add(new RenderedDigest(
                            image.pageNumber(), image.width(), image.height(), sha256(image.content()))));
        }
        return List.copyOf(rendered);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
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

    private record RenderedDigest(int pageNumber, int width, int height, String sha256) {}
}
