package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.document.DocumentProcessing.ExtractedTextBlock;
import com.rulepilot.ingestion.domain.RulebookUnderstanding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleStructureServiceTest {

    @Test
    void persistsWholeDocumentUnderstandingTogetherWithExistingStructure() {
        CapturingRepository repository = new CapturingRepository();
        RuleStructureService service = new RuleStructureService(
                new RuleStructureClassifier(),
                new RulePageChunker(),
                new RulebookUnderstandingBuilder(),
                repository);
        UUID versionId = UUID.randomUUID();

        service.organize(versionId, List.of(new ExtractedPage(1, "SETUP\nGive each player a Scout.", List.of(
                new ExtractedTextBlock(0, "SETUP", 100, 90, 200, 30),
                new ExtractedTextBlock(1, "Give each player a Scout.", 100, 150, 600, 40)))));

        assertThat(repository.understanding.pageBlocks()).hasSize(2);
        assertThat(repository.understanding.coverageLedger()).hasSameSizeAs(repository.understanding.inventory());
        assertThat(service.understanding(versionId).inventory()).hasSize(2);
    }

    private static final class CapturingRepository implements RuleStructureRepository {

        private RulebookUnderstanding understanding;

        @Override
        public void replace(
                UUID documentVersionId,
                List<DetectedRuleSection> sections,
                List<DetectedRuleChunk> chunks,
                RulebookUnderstanding understanding) {
            this.understanding = understanding;
        }

        @Override
        public List<DetectedRuleSection> findByDocumentVersion(UUID documentVersionId) {
            return List.of();
        }

        @Override
        public Optional<RulebookUnderstanding> findUnderstanding(UUID documentVersionId) {
            return Optional.ofNullable(understanding);
        }
    }
}
