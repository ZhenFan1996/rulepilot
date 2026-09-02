package com.rulepilot.visualaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.RenderedDocumentAvailable;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualAidIndexerTest {

    @Test
    void storesTypedGeometryForTheImmutableDocumentVersion() {
        UUID versionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        VisualLayoutExtractor extractor = mock(VisualLayoutExtractor.class);
        VisualRegionIndex index = mock(VisualRegionIndex.class);
        var metrics = new SimpleMeterRegistry();
        var regions = List.of(new Region(1, "PICTURE", 100, 200, 300, 400));
        when(extractor.configured()).thenReturn(true);
        when(documents.open(versionId)).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(extractor.extract(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VisualLayoutExtractor.Extraction("layout:test", 1, regions));

        new VisualAidIndexer(documents, extractor, index, metrics)
                .index(new RenderedDocumentAvailable(versionId, 1));

        verify(index).replace(versionId, "layout:test", 1, regions);
        assertThat(metrics.counter("rulepilot.visual_aid.index", "outcome", "succeeded").count())
                .isEqualTo(1);
    }

    @Test
    void pageIdentityMismatchIsLocalDegradationAndNeverPublishesAnIndex() {
        UUID versionId = UUID.randomUUID();
        DocumentProcessing documents = mock(DocumentProcessing.class);
        VisualLayoutExtractor extractor = mock(VisualLayoutExtractor.class);
        VisualRegionIndex index = mock(VisualRegionIndex.class);
        var metrics = new SimpleMeterRegistry();
        when(extractor.configured()).thenReturn(true);
        when(documents.open(versionId)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(extractor.extract(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VisualLayoutExtractor.Extraction("layout:test", 2, List.of()));

        new VisualAidIndexer(documents, extractor, index, metrics)
                .index(new RenderedDocumentAvailable(versionId, 1));

        verify(index, never()).replace(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList());
        assertThat(metrics.counter("rulepilot.visual_aid.index", "outcome", "failed").count())
                .isEqualTo(1);
    }

    @Test
    void unavailablePluginDoesNotReadTheDocument() {
        DocumentProcessing documents = mock(DocumentProcessing.class);
        VisualRegionIndex index = mock(VisualRegionIndex.class);

        new VisualAidIndexer(
                        documents,
                        VisualLayoutExtractor.unavailable(),
                        index,
                        new SimpleMeterRegistry())
                .index(new RenderedDocumentAvailable(UUID.randomUUID(), 1));

        verifyNoInteractions(documents, index);
    }
}
