package com.rulepilot.ingestion.adapter.in.web;

import com.rulepilot.ingestion.RuleChunkEmbeddingIndexer;
import com.rulepilot.ingestion.RuleChunkEmbeddingIndexer.EmbeddingIndexReport;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/document-versions/{versionId}/embeddings")
@Profile("!test")
public class RuleChunkEmbeddingController {

    private final RuleChunkEmbeddingIndexer indexer;

    public RuleChunkEmbeddingController(RuleChunkEmbeddingIndexer indexer) {
        this.indexer = indexer;
    }

    @PostMapping
    EmbeddingIndexReport index(@PathVariable UUID versionId) {
        return indexer.index(versionId);
    }
}
