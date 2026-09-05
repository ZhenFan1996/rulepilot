package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaRuleDocumentRepositoryTest {

    @Test
    void countsPageRowsWithoutSelectingPageEntities() {
        EntityManager entityManager = mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        UUID versionId = UUID.randomUUID();
        when(entityManager.createQuery(
                        "select count(p) from DocumentPageEntity p where p.documentVersionId = :versionId",
                        Long.class))
                .thenReturn(countQuery);
        when(countQuery.setParameter("versionId", versionId)).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(500L);
        JpaRuleDocumentRepository repository = new JpaRuleDocumentRepository(entityManager);

        assertThat(repository.countPages(versionId)).isEqualTo(500);

        verify(countQuery).getSingleResult();
    }

}
