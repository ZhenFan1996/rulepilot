package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.application.DocumentOutboxStore.TraceHeaders;
import com.rulepilot.document.application.DocumentTraceContextBridge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class JpaDocumentProcessingQueueTest {

    @Test
    void capturesTheCurrentTraceContextWithTheDurableOutboxEvent() {
        EntityManager entityManager = mock(EntityManager.class);
        DocumentTraceContextBridge traceContexts = mock(DocumentTraceContextBridge.class);
        TraceHeaders expected = new TraceHeaders(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");
        when(traceContexts.capture()).thenReturn(expected);
        JpaDocumentProcessingQueue queue = new JpaDocumentProcessingQueue(traceContexts);
        ReflectionTestUtils.setField(queue, "entityManager", entityManager);
        ArgumentCaptor<Object> persisted = ArgumentCaptor.forClass(Object.class);

        queue.enqueue(UUID.randomUUID(), Instant.parse("2026-08-25T00:00:00Z"));

        verify(entityManager, times(2)).persist(persisted.capture());
        OutboxEventEntity outbox = persisted.getAllValues().stream()
                .filter(OutboxEventEntity.class::isInstance)
                .map(OutboxEventEntity.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(outbox.traceParent).isEqualTo(expected.traceParent());
        assertThat(outbox.traceState).isEqualTo(expected.traceState());
        verify(traceContexts).capture();
    }

    @Test
    void doesNotCreateAnExecutionForAnAlreadyRemovedDocumentVersion() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(entityManager.createNativeQuery(sql.capture())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        JpaDocumentProcessingQueue queue = new JpaDocumentProcessingQueue();
        ReflectionTestUtils.setField(queue, "entityManager", entityManager);

        boolean started = queue.begin(
                new DocumentProcessingCommand(
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "v1",
                        DocumentProcessingStage.PARSE),
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-07T00:00:00Z"));

        assertThat(started).isFalse();
        assertThat(sql.getValue())
                .contains("from document_version")
                .contains("where id = :documentVersionId");
    }
}
