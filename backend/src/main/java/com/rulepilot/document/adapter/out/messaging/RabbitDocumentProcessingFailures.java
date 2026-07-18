package com.rulepilot.document.adapter.out.messaging;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingFailures;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RabbitDocumentProcessingFailures implements DocumentProcessingFailures {

    private final RabbitDocumentProcessingMessagePublisher messages;
    private final String retryExchange;
    private final String deadLetterExchange;
    private final String routingKey;

    public RabbitDocumentProcessingFailures(
            RabbitDocumentProcessingMessagePublisher messages,
            @Value("${rulepilot.document.messaging.retry-exchange}") String retryExchange,
            @Value("${rulepilot.document.messaging.dead-letter-exchange}") String deadLetterExchange,
            @Value("${rulepilot.document.messaging.routing-key}") String routingKey) {
        this.messages = messages;
        this.retryExchange = retryExchange;
        this.deadLetterExchange = deadLetterExchange;
        this.routingKey = routingKey;
    }

    @Override
    public void retry(DocumentProcessingCommand command, int attempt) {
        messages.send(
                retryExchange,
                routingKey + ".retry." + attempt,
                UUID.randomUUID(),
                "DocumentProcessingRequested",
                payload(command),
                attempt,
                "TRANSIENT_FAILURE");
    }

    @Override
    public void deadLetter(DocumentProcessingCommand command, int attempt, String errorCode) {
        messages.send(
                deadLetterExchange,
                routingKey,
                UUID.randomUUID(),
                "DocumentProcessingFailed",
                payload(command),
                attempt,
                errorCode);
    }

    private String payload(DocumentProcessingCommand command) {
        return "{\"schemaVersion\":" + command.schemaVersion()
                + ",\"documentVersionId\":\"" + command.documentVersionId()
                + "\",\"processingJobId\":\"" + command.processingJobId()
                + "\",\"pipelineVersion\":\"" + command.pipelineVersion()
                + "\",\"stage\":\"" + command.stage() + "\"}";
    }
}
