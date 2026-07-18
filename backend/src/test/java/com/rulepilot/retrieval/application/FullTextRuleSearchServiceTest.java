package com.rulepilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullTextRuleSearchServiceTest {

    @Test
    void normalizesQueryAndBoundsResultLimit() {
        var repository = new CapturingRepository();
        var service = new FullTextRuleSearchService(repository);
        UUID versionId = UUID.randomUUID();

        assertThat(service.search(versionId, "  final scoring  ", 200)).isEmpty();
        assertThat(repository.versionId).isEqualTo(versionId);
        assertThat(repository.query).isEqualTo("final scoring");
        assertThat(repository.limit).isEqualTo(20);
    }

    private static final class CapturingRepository implements FullTextRuleSearchRepository {
        private UUID versionId;
        private String query;
        private int limit;

        @Override
        public java.util.List<com.rulepilot.retrieval.evidence.RuleEvidenceHit> search(
                UUID documentVersionId, String searchQuery, int resultLimit) {
            versionId = documentVersionId;
            query = searchQuery;
            limit = resultLimit;
            return java.util.List.of();
        }
    }
}
