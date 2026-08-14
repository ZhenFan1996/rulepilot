package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class DocumentOutboxWakeupTest {

    @Test
    void dispatchesThePostCommitHintOnTheDedicatedExecutor() {
        DocumentOutboxPublisher publisher = mock(DocumentOutboxPublisher.class);
        var submitted = new java.util.ArrayList<Runnable>();
        TaskExecutor executor = submitted::add;
        var wakeup = new DocumentOutboxWakeup(publisher, executor);

        wakeup.afterCommit(new DocumentOutboxQueued());

        verifyNoInteractions(publisher);
        assertThat(submitted).singleElement().satisfies(Runnable::run);
        verify(publisher).publishCommittedEvents();
    }

    @Test
    void executorSaturationLeavesTheDurableEventForScheduledRecovery() {
        DocumentOutboxPublisher publisher = mock(DocumentOutboxPublisher.class);
        TaskExecutor rejected = task -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        var wakeup = new DocumentOutboxWakeup(publisher, rejected);

        wakeup.afterCommit(new DocumentOutboxQueued());

        verifyNoInteractions(publisher);
    }

    @Test
    void asynchronousPublicationFailureIsContainedForScheduledRecovery() {
        DocumentOutboxPublisher publisher = mock(DocumentOutboxPublisher.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(publisher)
                .publishCommittedEvents();
        var submitted = new java.util.ArrayList<Runnable>();
        TaskExecutor executor = submitted::add;
        var wakeup = new DocumentOutboxWakeup(publisher, executor);

        wakeup.afterCommit(new DocumentOutboxQueued());

        assertThatCode(() -> submitted.getFirst().run()).doesNotThrowAnyException();
        verify(publisher).publishCommittedEvents();
    }
}
