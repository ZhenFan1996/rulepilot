package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RuleStructureService implements RulebookUnderstandingCatalog {

    private final RulePageChunker chunker;
    private final RulebookUnderstandingBuilder understandingBuilder;
    private final RuleStructureRepository repository;

    public RuleStructureService(
            RulePageChunker chunker,
            RulebookUnderstandingBuilder understandingBuilder,
            RuleStructureRepository repository) {
        this.chunker = chunker;
        this.understandingBuilder = understandingBuilder;
        this.repository = repository;
    }

    @Transactional
    public void organize(UUID documentVersionId, List<DocumentProcessing.ExtractedPage> pages) {
        var understanding = understandingBuilder.build(pages);
        repository.replace(documentVersionId, chunker.chunk(pages, understanding), understanding);
    }

    @Override
    @Transactional(readOnly = true)
    public com.rulepilot.ingestion.layout.RulebookUnderstanding understanding(UUID documentVersionId) {
        return repository.findUnderstanding(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("rulebook understanding does not exist"));
    }
}
