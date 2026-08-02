package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.RuleDocument;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaRuleDocumentRepositoryTest {

    @Test
    void persistsAChangedDocumentTitleAlongsideItsOtherMutableMetadata() {
        EntityManager entityManager = mock(EntityManager.class);
        JpaRuleDocumentRepository repository = new JpaRuleDocumentRepository(entityManager);
        RuleDocument original = new RuleDocument(
                UUID.randomUUID(),
                null,
                "Lantern Relay rulebook EN v4 12pages",
                DocumentSourceType.BASE_RULEBOOK,
                "player",
                Instant.parse("2026-08-02T00:00:00Z"));
        RuleDocumentEntity stored = new RuleDocumentEntity(original);
        when(entityManager.find(RuleDocumentEntity.class, original.id())).thenReturn(stored);

        repository.update(original.withTitle("Lantern Relay"));

        assertThat(stored.toDomain().title()).isEqualTo("Lantern Relay");
        verify(entityManager).flush();
    }
}
