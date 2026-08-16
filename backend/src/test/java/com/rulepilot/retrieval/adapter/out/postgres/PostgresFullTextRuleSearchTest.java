package com.rulepilot.retrieval.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PostgresFullTextRuleSearchTest {

    @Test
    void leavesAnUnmatchedQueryEmptyInsteadOfRunningABroadOrFallback() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        when(query.setParameter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        PostgresFullTextRuleSearch search = new PostgresFullTextRuleSearch();
        ReflectionTestUtils.setField(search, "entityManager", entityManager);

        assertThat(search.search(
                        UUID.randomUUID(),
                        "中文口语问题 direct rule clause",
                        20))
                .isEmpty();
        verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.anyString());
        verify(query).getResultList();
    }
}
