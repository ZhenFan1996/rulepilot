package com.rulepilot.document.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JpaUploadedRulebookTeachingHandoffStoreTransactionTest {

    @Test
    void workerClaimsAndTerminalWritesCommitInIndependentTransactions() throws Exception {
        assertRequiresNew("claimReady", int.class, Instant.class);
        assertRequiresNew("claimReadyForDocument", UUID.class, int.class, Instant.class);
        assertRequiresNew("retry", UUID.class, UUID.class, String.class, Instant.class);
        assertRequiresNew("failUnusableDocuments", Instant.class);
        assertRequiresNew("completeLaunch", UUID.class, UUID.class, Instant.class);
        assertRequiresNew("failLaunch", UUID.class, String.class, Instant.class);
        assertRequiresNew("failInterruptedLaunches", Instant.class);
    }

    private void assertRequiresNew(String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = JpaUploadedRulebookTeachingHandoffStore.class
                .getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional)
                .as("%s must create a real worker transaction", methodName)
                .isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
