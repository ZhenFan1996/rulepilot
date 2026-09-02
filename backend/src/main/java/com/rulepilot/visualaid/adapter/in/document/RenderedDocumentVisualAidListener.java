package com.rulepilot.visualaid.adapter.in.document;

import com.rulepilot.document.RenderedDocumentAvailable;
import com.rulepilot.visualaid.application.VisualAidIndexer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Plugin entrypoint: document ingestion publishes an event and has no dependency on visual-aid internals. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class RenderedDocumentVisualAidListener {

    private final VisualAidIndexer indexer;

    public RenderedDocumentVisualAidListener(VisualAidIndexer indexer) {
        this.indexer = indexer;
    }

    @EventListener
    public void onRendered(RenderedDocumentAvailable event) {
        indexer.index(event);
    }
}
