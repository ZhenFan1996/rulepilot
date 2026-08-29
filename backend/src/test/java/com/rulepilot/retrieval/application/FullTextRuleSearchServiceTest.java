package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullTextRuleSearchServiceTest {

    @Test
    void normalizesQueryAndPreservesTheRequestedResultCount() {
        var repository = new CapturingRepository();
        var service = new FullTextRuleSearchService(repository);
        UUID versionId = UUID.randomUUID();

        assertThat(service.search(versionId, "  final scoring  ", 200)).isEmpty();
        assertThat(repository.versionId).isEqualTo(versionId);
        assertThat(repository.query).isEqualTo("final scoring");
        assertThat(repository.limit).isEqualTo(200);
        assertThatThrownBy(() -> service.search(versionId, "scoring", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesTheStableOffsetToTheRepositoryBeforeMaterializingThePage() {
        var repository = new CapturingRepository();
        var service = new FullTextRuleSearchService(repository);

        assertThat(service.search(UUID.randomUUID(), "turn order", 120, 7)).isEmpty();

        assertThat(repository.offset).isEqualTo(120);
        assertThat(repository.limit).isEqualTo(7);
    }

    private static final class CapturingRepository implements FullTextRuleSearchRepository {
        private UUID versionId;
        private String query;
        private int limit;
        private int offset;

        @Override
        public java.util.List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> search(
                UUID documentVersionId, String searchQuery, int resultLimit) {
            versionId = documentVersionId;
            query = searchQuery;
            limit = resultLimit;
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> search(
                UUID documentVersionId, String searchQuery, int resultOffset, int resultLimit) {
            versionId = documentVersionId;
            query = searchQuery;
            offset = resultOffset;
            limit = resultLimit;
            return java.util.List.of();
        }
    }
}
