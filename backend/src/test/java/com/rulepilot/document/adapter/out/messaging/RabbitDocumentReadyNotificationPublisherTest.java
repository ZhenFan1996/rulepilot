package com.rulepilot.document.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitDocumentReadyNotificationPublisherTest {

    @Test
    void sendsABoundedPersistentWakeupWithTheReadyMilestone() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        Instant readyAt = Instant.parse("2026-08-13T08:00:00Z");
        var publisher = new RabbitDocumentReadyNotificationPublisher(
                rabbit,
                "rulepilot.document.ready",
                "document.ready.v1");
        UUID versionId = UUID.randomUUID();

        publisher.publish(versionId, readyAt);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(
                org.mockito.ArgumentMatchers.eq("rulepilot.document.ready"),
                org.mockito.ArgumentMatchers.eq("document.ready.v1"),
                message.capture());
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getHeader("rulepilot-event-type").toString())
                .isEqualTo("DocumentReady");
        assertThat(message.getValue().getMessageProperties().getHeader("rulepilot-event-id").toString())
                .isNotBlank();
        var payload = new ObjectMapper().readTree(message.getValue().getBody());
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("documentVersionId").asText()).isEqualTo(versionId.toString());
        assertThat(payload.path("readyAt").asText()).isEqualTo(readyAt.toString());
    }
}
