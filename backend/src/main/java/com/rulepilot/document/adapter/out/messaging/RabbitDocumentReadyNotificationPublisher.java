package com.rulepilot.document.adapter.out.messaging;

import com.rulepilot.document.DocumentReadyNotifications;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class RabbitDocumentReadyNotificationPublisher implements DocumentReadyNotifications {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public RabbitDocumentReadyNotificationPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${rulepilot.document.ready-notification.exchange}") String exchange,
            @Value("${rulepilot.document.ready-notification.routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = required(exchange, "exchange");
        this.routingKey = required(routingKey, "routing key");
    }

    @Override
    public void publish(UUID documentVersionId, Instant readyAt) {
        if (documentVersionId == null || readyAt == null) {
            throw new IllegalArgumentException("ready document version and persisted timestamp are required");
        }
        UUID eventId = UUID.randomUUID();
        String payload = "{\"schemaVersion\":1,\"documentVersionId\":\"" + documentVersionId
                + "\",\"readyAt\":\"" + readyAt + "\"}";
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("rulepilot-event-id", eventId.toString())
                .setHeader("rulepilot-event-type", "DocumentReady")
                .build();
        // This is a wake-up, not the source of truth. Avoid a publisher-confirm wait on the PDF worker critical path;
        // the API's bounded database reconciliation scan recovers an unroutable or lost notification.
        rabbitTemplate.send(exchange, routingKey, message);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("document READY notification " + label + " is required");
        }
        return value.strip();
    }
}
