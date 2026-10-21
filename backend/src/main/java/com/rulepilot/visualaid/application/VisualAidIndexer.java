package com.rulepilot.visualaid.application;

import com.rulepilot.document.DocumentProcessing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds one reusable source index when teaching first requests illustrations. */
@Service
@Profile("!test")
public class VisualAidIndexer {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisualAidIndexer.class);

    private final DocumentProcessing documents;
    private final VisualLayoutExtractor extractor;
    private final VisualRegionIndex index;
    private final MeterRegistry metrics;

    public VisualAidIndexer(
            DocumentProcessing documents,
            VisualLayoutExtractor extractor,
            VisualRegionIndex index,
            MeterRegistry metrics) {
        this.documents = documents;
        this.extractor = extractor;
        this.index = index;
        this.metrics = metrics;
    }

    public synchronized void ensure(java.util.UUID version) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("layout preparation interrupted");
        if (!extractor.configured() || "docling-heron-raw-v1".equals(index.source(version))) return;
        int pageCount = documents.pageCount(version);
        Timer.Sample duration = Timer.start(metrics);
        String outcome = "failed";
        try (InputStream input = documents.open(version)) {
            VisualLayoutExtractor.Extraction extraction = extractor.extract(input);
            if (extraction.pageCount() != pageCount) {
                throw new IllegalStateException("visual layout page count does not match the rendered document");
            }
            index.replace(
                    version,
                    extraction.source(),
                    extraction.pageCount(),
                    extraction.regions());
            outcome = "succeeded";
            LOGGER.info(
                    "Visual aid index completed for documentVersionId={}, pages={}, regions={}, source={}",
                    version,
                    extraction.pageCount(),
                    extraction.regions().size(),
                    extraction.source());
        } catch (IOException | RuntimeException failure) {
            // Visual help is optional. Preserve the extracted text and original page evidence.
            LOGGER.warn(
                    "Visual aid index failed for documentVersionId={}; teaching will retain original page evidence",
                    version,
                    failure);
        } finally {
            metrics.counter("rulepilot.visual_aid.index", "outcome", outcome).increment();
            duration.stop(Timer.builder("rulepilot.visual_aid.index.duration")
                    .description("End-to-end optional visual layout indexing duration")
                    .register(metrics));
        }
    }
}
