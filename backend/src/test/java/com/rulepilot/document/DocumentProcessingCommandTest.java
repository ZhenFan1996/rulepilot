package com.rulepilot.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentProcessingCommandTest {

    @Test
    void newParseCommandsSkipTheAlreadyPersistedChunkStage() {
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var parse = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.PARSE);

        assertThat(parse.nextStage()).isEqualTo(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED));
    }

    @Test
    void legacyChunkCommandsRemainACompatibleBridgeToEmbed() {
        UUID documentVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var chunk = new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.CHUNK);

        assertThat(chunk.nextStage()).isEqualTo(new DocumentProcessingCommand(
                1, documentVersionId, jobId, "v1", DocumentProcessingStage.EMBED));
    }
}
