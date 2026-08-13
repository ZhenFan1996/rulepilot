package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JpaOfficialRulebookImportJobRepositoryTransactionTest {

    @Test
    void exactDocumentClaimCreatesAnIndependentTransactionForItsPessimisticLock() throws Exception {
        Transactional transactional = JpaOfficialRulebookImportJobRepository.class
                .getDeclaredMethod("claimReadyTeachingForDocument", UUID.class, int.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
