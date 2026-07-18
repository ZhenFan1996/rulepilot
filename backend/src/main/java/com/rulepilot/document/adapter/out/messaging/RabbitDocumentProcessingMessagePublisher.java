package com.rulepilot.document.adapter.out.messaging;

import com.rulepilot.document.application.DocumentProcessingMessagePublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RabbitDocumentProcessingMessagePublisher implements DocumentProcessingMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final Duration confirmTimeout;

    public RabbitDocumentProcessingMessagePublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${rulepilot.document.messaging.exchange}") String exchange,
            @Value("${rulepilot.document.messaging.routing-key}") String routingKey,
            @Value("${rulepilot.document.messaging.confirm-timeout}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(UUID eventId, String eventType, String payload) {
        send(exchange, routingKey, eventId, eventType, payload, 1, null);
    }

    void send(
            String targetExchange,
            String targetRoutingKey,
            UUID eventId,
            String eventType,
            String payload,
            int attempt,
            String errorCode) {
        var builder = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("rulepilot-event-id", eventId.toString())
                .setHeader("rulepilot-event-type", eventType)
                .setHeader("rulepilot-attempt", attempt);
        if (errorCode != null) {
            builder.setHeader("rulepilot-error-code", errorCode);
        }
        Message message = builder.build();
        CorrelationData correlation = new CorrelationData(eventId.toString());
        rabbitTemplate.send(targetExchange, targetRoutingKey, message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation
                    .getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejected outbox event " + eventId + ": " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ could not route event " + eventId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while confirming outbox event " + eventId, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("RabbitMQ did not confirm outbox event " + eventId, exception);
        }
    }
}
