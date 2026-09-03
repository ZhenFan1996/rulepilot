package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.ingestion.EmbeddingProvider;
import com.rulepilot.ingestion.EmbeddingProvider.EmbeddingVector;
import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RuleChunkEmbeddingServiceTest {

    @Test
    void attributesPendingProviderAndPersistenceWithoutChangingCompleteIndexOrdering() {
        EmbeddingProvider provider = Mockito.mock(EmbeddingProvider.class);
        RuleChunkEmbeddingRepository chunks = Mockito.mock(RuleChunkEmbeddingRepository.class);
        var metrics = new SimpleMeterRegistry();
        UUID documentVersionId = UUID.randomUUID();
        var first = new RuleChunkEmbeddingRepository.EmbeddableChunk(UUID.randomUUID(), "Setup", "Place the board.");
        var second = new RuleChunkEmbeddingRepository.EmbeddableChunk(UUID.randomUUID(), "Turns", "Take one action.");
        var firstVector = new EmbeddingVector(List.of(1.0f, 0.0f));
        var secondVector = new EmbeddingVector(List.of(0.0f, 1.0f));
        when(provider.id()).thenReturn("bounded-provider");
        when(provider.dimensions()).thenReturn(2);
        when(provider.batchSize()).thenReturn(10);
        when(chunks.findPending(documentVersionId, "bounded-provider", 10))
                .thenReturn(List.of(first, second), List.of());
        when(chunks.coverage(documentVersionId, "bounded-provider"))
                .thenReturn(new EmbeddingIndexCoverage(2, 2));
        when(provider.embed(List.of(first.embeddingText(), second.embeddingText())))
                .thenReturn(List.of(firstVector, secondVector));

        new RuleChunkEmbeddingService(provider, chunks, metrics).index(documentVersionId);

        var order = inOrder(chunks, provider);
        order.verify(chunks).findPending(documentVersionId, "bounded-provider", 10);
        order.verify(provider).embed(List.of(first.embeddingText(), second.embeddingText()));
        order.verify(chunks)
                .saveBatch(
                        eq(List.of(
                                new RuleChunkEmbeddingRepository.IndexedChunk(first.id(), firstVector),
                                new RuleChunkEmbeddingRepository.IndexedChunk(second.id(), secondVector))),
                        eq("bounded-provider"),
                        any());
        order.verify(chunks).findPending(documentVersionId, "bounded-provider", 10);
        order.verify(chunks).coverage(documentVersionId, "bounded-provider");
        assertThat(metrics
                        .timer(RuleChunkEmbeddingService.PHASE_DURATION_METRIC, "phase", "pending-load")
                        .count())
                .isEqualTo(2);
        assertThat(metrics.timer(RuleChunkEmbeddingService.PHASE_DURATION_METRIC, "phase", "provider").count())
                .isEqualTo(1);
        assertThat(metrics
                        .timer(RuleChunkEmbeddingService.PHASE_DURATION_METRIC, "phase", "persistence")
                        .count())
                .isEqualTo(1);
        assertThat(metrics.find(RuleChunkEmbeddingService.PHASE_DURATION_METRIC).timers()).hasSize(3);
    }

    @Test
    void recordsOnlyThePendingReadWhenTheVersionIsAlreadyIndexed() {
        EmbeddingProvider provider = Mockito.mock(EmbeddingProvider.class);
        RuleChunkEmbeddingRepository chunks = Mockito.mock(RuleChunkEmbeddingRepository.class);
        var metrics = new SimpleMeterRegistry();
        UUID documentVersionId = UUID.randomUUID();
        when(provider.id()).thenReturn("bounded-provider");
        when(provider.dimensions()).thenReturn(2);
        when(provider.batchSize()).thenReturn(10);
        when(chunks.findPending(documentVersionId, "bounded-provider", 10)).thenReturn(List.of());
        when(chunks.coverage(documentVersionId, "bounded-provider"))
                .thenReturn(new EmbeddingIndexCoverage(3, 3));

        new RuleChunkEmbeddingService(provider, chunks, metrics).index(documentVersionId);

        verify(chunks).findPending(documentVersionId, "bounded-provider", 10);
        verify(chunks).coverage(documentVersionId, "bounded-provider");
        verifyNoMoreInteractions(chunks);
        assertThat(metrics.find(RuleChunkEmbeddingService.PHASE_DURATION_METRIC).timers())
                .singleElement()
                .satisfies(timer -> assertThat(timer.getId().getTag("phase")).isEqualTo("pending-load"));
    }

    @Test
    void checkpointsEachProviderBatchSoRetryResumesAfterAProviderFailure() {
        EmbeddingProvider provider = Mockito.mock(EmbeddingProvider.class);
        RuleChunkEmbeddingRepository chunks = Mockito.mock(RuleChunkEmbeddingRepository.class);
        UUID documentVersionId = UUID.randomUUID();
        var first = new RuleChunkEmbeddingRepository.EmbeddableChunk(UUID.randomUUID(), "Setup", "Place the board.");
        var second = new RuleChunkEmbeddingRepository.EmbeddableChunk(UUID.randomUUID(), "Turns", "Take one action.");
        var firstVector = new EmbeddingVector(List.of(1.0f, 0.0f));
        var secondVector = new EmbeddingVector(List.of(0.0f, 1.0f));
        when(provider.id()).thenReturn("bounded-provider");
        when(provider.dimensions()).thenReturn(2);
        when(provider.batchSize()).thenReturn(1);
        when(chunks.findPending(documentVersionId, "bounded-provider", 1))
                .thenReturn(List.of(first), List.of(second), List.of(second), List.of());
        when(provider.embed(List.of(first.embeddingText()))).thenReturn(List.of(firstVector));
        when(provider.embed(List.of(second.embeddingText())))
                .thenThrow(new IllegalStateException("provider timeout"))
                .thenReturn(List.of(secondVector));
        when(chunks.coverage(documentVersionId, "bounded-provider"))
                .thenReturn(new EmbeddingIndexCoverage(2, 2));
        var service = new RuleChunkEmbeddingService(provider, chunks, new SimpleMeterRegistry());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.index(documentVersionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider timeout");
        verify(chunks)
                .saveBatch(
                        eq(List.of(new RuleChunkEmbeddingRepository.IndexedChunk(first.id(), firstVector))),
                        eq("bounded-provider"),
                        any());

        service.index(documentVersionId);

        verify(chunks)
                .saveBatch(
                        eq(List.of(new RuleChunkEmbeddingRepository.IndexedChunk(second.id(), secondVector))),
                        eq("bounded-provider"),
                        any());
        verify(provider, Mockito.times(1)).embed(List.of(first.embeddingText()));
        verify(provider, Mockito.times(2)).embed(List.of(second.embeddingText()));
    }
}
