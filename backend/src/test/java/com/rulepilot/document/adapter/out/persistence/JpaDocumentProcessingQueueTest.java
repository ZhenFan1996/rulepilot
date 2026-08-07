package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingStage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class JpaDocumentProcessingQueueTest {

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
