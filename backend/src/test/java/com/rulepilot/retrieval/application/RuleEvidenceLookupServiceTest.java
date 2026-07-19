package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleEvidenceLookupServiceTest {

    @Test
    void validatesAndNormalizesAdjacentLookupScope() {
        UUID versionId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        RecordingRepository repository = new RecordingRepository();
        var lookup = new RuleEvidenceLookupService(repository);

        lookup.findAdjacent(versionId, Set.of(anchorId), 1, Set.of("setup"));

        assertThat(repository.versionId).isEqualTo(versionId);
        assertThat(repository.anchorIds).containsExactly(anchorId);
        assertThat(repository.radius).isEqualTo(1);
        assertThat(repository.sectionTypes).containsExactly("SETUP");

        assertThatThrownBy(() -> lookup.findAdjacent(versionId, Set.of(anchorId), 3, Set.of("SETUP")))
                .isInstanceOf(IllegalArgumentException.class);
        lookup.findAdjacent(versionId, Set.of(anchorId), 1, Set.of());
        assertThat(repository.sectionTypes).isEmpty();
    }

    private static final class RecordingRepository implements RuleEvidenceLookupRepository {
        private UUID versionId;
        private Set<UUID> anchorIds;
        private int radius;
        private Set<String> sectionTypes;

        @Override
        public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
            return List.of();
        }

        @Override
        public List<RuleEvidenceHit> findAdjacent(
                UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
            this.versionId = documentVersionId;
            this.anchorIds = anchorChunkIds;
            this.radius = radius;
            this.sectionTypes = sectionTypes;
            return List.of();
        }
    }
}
