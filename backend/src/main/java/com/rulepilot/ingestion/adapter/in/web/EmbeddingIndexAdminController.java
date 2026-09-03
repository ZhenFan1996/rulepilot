package com.rulepilot.ingestion.adapter.in.web;

import com.rulepilot.ingestion.EmbeddingIndexCoverage;
import com.rulepilot.ingestion.application.RuleChunkEmbeddingService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/document-versions/{versionId}/embedding-index")
@Profile("!test")
public class EmbeddingIndexAdminController {

    private final RuleChunkEmbeddingService embeddings;

    public EmbeddingIndexAdminController(RuleChunkEmbeddingService embeddings) {
        this.embeddings = embeddings;
    }

    @PostMapping
    EmbeddingIndexCoverage rebuild(@PathVariable UUID versionId) {
        return embeddings.index(versionId);
    }
}
