package com.rulepilot.visualaid.adapter.out.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.visualaid.application.VisualLayoutExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Local raw layout detection; semantic selection belongs to the chapter Agent. */
@Component
public final class DoclingVisualLayoutExtractor implements VisualLayoutExtractor {
    private final String python;
    private final Path modelDirectory;
    private final Duration timeout;
    // Linux Heron measured 491 MiB peak RSS; production reserves 600 MiB for one detector alongside the JVM.
    private final Semaphore admission = new Semaphore(1, true);
    private final ObjectMapper json = new ObjectMapper();

    public DoclingVisualLayoutExtractor(
            @Value("${rulepilot.docling.python:/opt/layout/bin/python}") String python,
            @Value("${rulepilot.docling.model-directory:/opt/layout/model}") Path modelDirectory,
            @Value("${rulepilot.docling.timeout:PT4M}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("layout deadline must be positive");
        this.python = python;
        this.modelDirectory = modelDirectory;
        this.timeout = timeout;
    }

    @Override public boolean configured() { return Files.isRegularFile(modelDirectory.resolve("model.onnx")); }

    @Override public Extraction extract(InputStream source) {
        Path work = null;
        Process process = null;
        boolean acquired = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            acquired = admission.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) throw new IllegalStateException("layout extraction capacity unavailable before deadline");
            work = Files.createTempDirectory("rulepilot-layout-");
            Path pdf = work.resolve("source.pdf"), script = work.resolve("layout.py"), result = work.resolve("result.json");
            Files.copy(source, pdf);
            try (var input = new ClassPathResource("visual/docling_layout.py").getInputStream()) { Files.copy(input, script); }
            Path errors = work.resolve("stderr.log");
            process = new ProcessBuilder(python, script.toString(), pdf.toString(), modelDirectory.toString(), result.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start();
            if (!process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("layout extraction exceeded its configured active-work deadline");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("layout extraction failed with exit code " + process.exitValue() + ": " + Files.readString(errors));
            return json.readValue(result.toFile(), Extraction.class);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("layout extraction interrupted", cancelled);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not extract source illustrations", failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly().onExit().join();
            if (acquired) admission.release();
            if (work != null) {
                try (var files = Files.list(work)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                    Files.deleteIfExists(work);
                } catch (IOException cleanupFailure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not remove temporary layout input", cleanupFailure);
                }
            }
        }
    }
}
