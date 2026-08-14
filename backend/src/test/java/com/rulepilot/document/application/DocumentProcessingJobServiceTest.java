package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rulepilot.document.DocumentProcessingStage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DocumentProcessingJobServiceTest {

    @Test
    void startsFromTheProductionProfileWithTheExplicitDependencyConstructor() {
        DocumentProcessingJobStore jobs = mock(DocumentProcessingJobStore.class);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DocumentProcessingJobStore.class, () -> jobs);
            context.register(DocumentProcessingJobService.class);
            context.refresh();

            assertThat(context.getBean(DocumentProcessingJobService.class)).isNotNull();
        }
    }

    @Test
    void returnsTheSameTerminalTimestampThatItPersists() {
        DocumentProcessingJobStore jobs = mock(DocumentProcessingJobStore.class);
        Instant completedAt = Instant.parse("2026-08-13T08:00:00Z");
        var service = new DocumentProcessingJobService(
                jobs, Clock.fixed(completedAt, ZoneOffset.UTC));
        UUID jobId = UUID.randomUUID();

        Instant persisted = service.completed(jobId, DocumentProcessingStage.EMBED);

        assertThat(persisted).isEqualTo(completedAt);
        verify(jobs).update(jobId, DocumentProcessingStage.EMBED, "COMPLETED", completedAt);
    }
}
