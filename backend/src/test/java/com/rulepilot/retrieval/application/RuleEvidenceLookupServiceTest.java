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
    void preservesCompletePageAndAdjacentLookupScopeWhileNormalizingSectionIdentities() {
        UUID versionId = UUID.randomUUID();
        Set<UUID> anchorIds = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(ignored -> UUID.randomUUID())
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> pageNumbers = java.util.stream.IntStream.rangeClosed(1, 8)
                .boxed()
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sectionTypes = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "section_" + index)
                .collect(java.util.stream.Collectors.toSet());
        RecordingRepository repository = new RecordingRepository();
        var lookup = new RuleEvidenceLookupService(repository);

        assertThat(lookup.canonicalChunkCount(versionId)).isEqualTo(42);
        lookup.findByPageNumbers(versionId, pageNumbers);
        lookup.findAdjacent(versionId, anchorIds, 3, sectionTypes);

        assertThat(repository.versionId).isEqualTo(versionId);
        assertThat(repository.pageNumbers).containsExactlyInAnyOrderElementsOf(pageNumbers);
        assertThat(repository.anchorIds).containsExactlyInAnyOrderElementsOf(anchorIds);
        assertThat(repository.radius).isEqualTo(3);
        assertThat(repository.sectionTypes)
                .containsExactlyInAnyOrderElementsOf(sectionTypes.stream().map(String::toUpperCase).toList());

        assertThatThrownBy(() -> lookup.findAdjacent(versionId, anchorIds, 0, Set.of("SETUP")))
                .isInstanceOf(IllegalArgumentException.class);
        lookup.findAdjacent(versionId, anchorIds, 1, Set.of());
        assertThat(repository.sectionTypes).isEmpty();

        lookup.findByPageNumbers(versionId, Set.of(2, 3), 40, 6);
        assertThat(repository.offset).isEqualTo(40);
        assertThat(repository.limit).isEqualTo(6);
        lookup.findAdjacent(versionId, Set.of(anchorIds.iterator().next()), 1, Set.of(), 70, 4);
        assertThat(repository.offset).isEqualTo(70);
        assertThat(repository.limit).isEqualTo(4);
    }

    private static final class RecordingRepository implements RuleEvidenceLookupRepository {
        private UUID versionId;
        private Set<Integer> pageNumbers;
        private Set<UUID> anchorIds;
        private int radius;
        private Set<String> sectionTypes;
        private int offset;
        private int limit;

        @Override
        public int canonicalChunkCount(UUID documentVersionId) {
            versionId = documentVersionId;
            return 42;
        }

        @Override
        public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
            return List.of();
        }

        @Override
        public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> requestedPageNumbers) {
            versionId = documentVersionId;
            pageNumbers = requestedPageNumbers;
            return List.of();
        }

        @Override
        public List<RuleEvidenceHit> findByPageNumbers(
                UUID documentVersionId, Set<Integer> requestedPageNumbers, int resultOffset, int resultLimit) {
            versionId = documentVersionId;
            pageNumbers = requestedPageNumbers;
            offset = resultOffset;
            limit = resultLimit;
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

        @Override
        public List<RuleEvidenceHit> findAdjacent(
                UUID documentVersionId,
                Set<UUID> anchorChunkIds,
                int radius,
                Set<String> sectionTypes,
                int resultOffset,
                int resultLimit) {
            this.versionId = documentVersionId;
            this.anchorIds = anchorChunkIds;
            this.radius = radius;
            this.sectionTypes = sectionTypes;
            this.offset = resultOffset;
            this.limit = resultLimit;
            return List.of();
        }
    }
}
