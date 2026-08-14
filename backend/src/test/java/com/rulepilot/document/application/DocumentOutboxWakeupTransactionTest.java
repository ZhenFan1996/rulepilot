package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@SpringJUnitConfig(DocumentOutboxWakeupTransactionTest.ConfigurationUnderTest.class)
class DocumentOutboxWakeupTransactionTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TestTransactionManager transactions;

    @Autowired
    private DocumentOutboxPublisher publisher;

    @Test
    void listensOnlyAfterTheUploadTransactionCommits() throws Exception {
        TransactionalEventListener listener = DocumentOutboxWakeup.class
                .getDeclaredMethod("afterCommit", DocumentOutboxQueued.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isFalse();
    }

    @Test
    void dispatchesOnlyAfterCommitAndNotAfterRollback() {
        var committed = transactions.getTransaction(new DefaultTransactionDefinition());
        events.publishEvent(new DocumentOutboxQueued());
        verifyNoInteractions(publisher);

        transactions.commit(committed);
        verify(publisher).publishCommittedEvents();

        var rolledBack = transactions.getTransaction(new DefaultTransactionDefinition());
        events.publishEvent(new DocumentOutboxQueued());
        transactions.rollback(rolledBack);
        verify(publisher, times(1)).publishCommittedEvents();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class ConfigurationUnderTest {

        @Bean
        DocumentOutboxPublisher publisher() {
            return mock(DocumentOutboxPublisher.class);
        }

        @Bean
        TaskExecutor documentOutboxWakeupExecutor() {
            return Runnable::run;
        }

        @Bean
        DocumentOutboxWakeup wakeup(DocumentOutboxPublisher publisher, TaskExecutor executor) {
            return new DocumentOutboxWakeup(publisher, executor);
        }

        @Bean
        TestTransactionManager transactionManager() {
            return new TestTransactionManager();
        }
    }

    static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
