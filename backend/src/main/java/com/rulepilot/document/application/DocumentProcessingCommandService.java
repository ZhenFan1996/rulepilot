package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingCommand;
import com.rulepilot.document.DocumentProcessingCommands;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class DocumentProcessingCommandService implements DocumentProcessingCommands {

    private final DocumentProcessingMessagePublisher messages;

    public DocumentProcessingCommandService(DocumentProcessingMessagePublisher messages) {
        this.messages = messages;
    }

    @Override
    public void publish(DocumentProcessingCommand command) {
        messages.publish(UUID.randomUUID(), "DocumentProcessingRequested", payload(command));
    }

    private String payload(DocumentProcessingCommand command) {
        return "{\"schemaVersion\":" + command.schemaVersion()
                + ",\"documentVersionId\":\"" + command.documentVersionId()
                + "\",\"processingJobId\":\"" + command.processingJobId()
                + "\",\"pipelineVersion\":\"" + command.pipelineVersion()
                + "\",\"stage\":\"" + command.stage() + "\"}";
    }
}
