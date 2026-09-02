package com.rulepilot.visualaid.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.RenderedDocumentAvailable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Optional ingestion reaction owned entirely by the visual-aid module. */
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

    public void index(RenderedDocumentAvailable event) {
        if (!extractor.configured()) return;
        Timer.Sample duration = Timer.start(metrics);
        String outcome = "failed";
        try (InputStream input = documents.open(event.documentVersionId())) {
            VisualLayoutExtractor.Extraction extraction = extractor.extract(input);
            if (extraction.pageCount() != event.pageCount()) {
                throw new IllegalStateException("visual layout page count does not match the rendered document");
            }
            index.replace(
                    event.documentVersionId(),
                    extraction.source(),
                    extraction.pageCount(),
                    extraction.regions());
            outcome = "succeeded";
            LOGGER.info(
                    "Visual aid index completed for documentVersionId={}, pages={}, regions={}, source={}",
                    event.documentVersionId(),
                    extraction.pageCount(),
                    extraction.regions().size(),
                    extraction.source());
        } catch (IOException | RuntimeException failure) {
            // Visual help is optional. Preserve the already extracted text and let local pixel proposals remain usable.
            LOGGER.warn(
                    "Visual aid index failed for documentVersionId={}; teaching will use local candidates",
                    event.documentVersionId(),
                    failure);
        } finally {
            metrics.counter("rulepilot.visual_aid.index", "outcome", outcome).increment();
            duration.stop(Timer.builder("rulepilot.visual_aid.index.duration")
                    .description("End-to-end optional visual layout indexing duration")
                    .register(metrics));
        }
    }
}
