package com.rulepilot.ingestion.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.ingestion.application.RuleChunkEmbeddingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EmbeddingIndexAdminControllerTest {

    @Test
    void returnsTheVerifiedCurrentProviderCoverage() {
        RuleChunkEmbeddingService embeddings = Mockito.mock(RuleChunkEmbeddingService.class);
        UUID versionId = UUID.randomUUID();
        var coverage = new EmbeddingIndexCoverage(48, 48);
        when(embeddings.index(versionId)).thenReturn(coverage);

        assertThat(new EmbeddingIndexAdminController(embeddings).rebuild(versionId))
                .isEqualTo(coverage);

        verify(embeddings).index(versionId);
    }
}
