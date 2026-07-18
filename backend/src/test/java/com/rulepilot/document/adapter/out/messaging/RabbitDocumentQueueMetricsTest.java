package com.rulepilot.document.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class RabbitDocumentQueueMetricsTest {

    @Test
    void publishesDepthForEveryProcessingQueue() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        var registry = new SimpleMeterRegistry();
        when(rabbitAdmin.getQueueInfo("documents")).thenReturn(new QueueInformation("documents", 7, 1));
        when(rabbitAdmin.getQueueInfo("documents.retry.2"))
                .thenReturn(new QueueInformation("documents.retry.2", 2, 0));
        when(rabbitAdmin.getQueueInfo("documents.retry.3"))
                .thenReturn(new QueueInformation("documents.retry.3", 1, 0));
        when(rabbitAdmin.getQueueInfo("documents.dlq"))
                .thenReturn(new QueueInformation("documents.dlq", 4, 0));
        var metrics = new RabbitDocumentQueueMetrics(rabbitAdmin, registry, "documents", "documents.dlq", 3);

        metrics.refresh();

        assertThat(depth(registry, "main")).isEqualTo(7);
        assertThat(depth(registry, "retry-2")).isEqualTo(2);
        assertThat(depth(registry, "retry-3")).isEqualTo(1);
        assertThat(depth(registry, "dead-letter")).isEqualTo(4);
    }

    private double depth(SimpleMeterRegistry registry, String queue) {
        return registry.get("rulepilot.document.processing.queue.messages").tag("queue", queue).gauge().value();
    }
}
