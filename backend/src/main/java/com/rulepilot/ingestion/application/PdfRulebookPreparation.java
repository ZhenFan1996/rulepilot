package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentPageImageStore.RenderedPageImage;
import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Produces text/layout and visual page representations from one trusted PDF source.
 *
 * <p>The extracted-pages consumer is called exactly once before any page-image consumer invocation. Implementations
 * run synchronously and close the supplied input stream.
 */
public interface PdfRulebookPreparation {

    void prepare(
            InputStream input,
            Consumer<List<ExtractedPage>> extractedPagesConsumer,
            Consumer<RenderedPageImage> pageImageConsumer);
}
