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
        when(chunks.findPending(documentVersionId, "bounded-provider")).thenReturn(List.of(first, second));
        when(provider.embed(List.of(first.embeddingText(), second.embeddingText())))
                .thenReturn(List.of(firstVector, secondVector));

        new RuleChunkEmbeddingService(provider, chunks, metrics).index(documentVersionId);

        var order = inOrder(chunks, provider);
        order.verify(chunks).findPending(documentVersionId, "bounded-provider");
        order.verify(provider).embed(List.of(first.embeddingText(), second.embeddingText()));
        order.verify(chunks).save(eq(first.id()), eq(firstVector), eq("bounded-provider"), any());
        order.verify(chunks).save(eq(second.id()), eq(secondVector), eq("bounded-provider"), any());
        assertThat(metrics
                        .timer(RuleChunkEmbeddingService.PHASE_DURATION_METRIC, "phase", "pending-load")
                        .count())
                .isEqualTo(1);
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
        when(chunks.findPending(documentVersionId, "bounded-provider")).thenReturn(List.of());

        new RuleChunkEmbeddingService(provider, chunks, metrics).index(documentVersionId);

        verify(chunks).findPending(documentVersionId, "bounded-provider");
        verifyNoMoreInteractions(chunks);
        assertThat(metrics.find(RuleChunkEmbeddingService.PHASE_DURATION_METRIC).timers())
                .singleElement()
                .satisfies(timer -> assertThat(timer.getId().getTag("phase")).isEqualTo("pending-load"));
    }
}
