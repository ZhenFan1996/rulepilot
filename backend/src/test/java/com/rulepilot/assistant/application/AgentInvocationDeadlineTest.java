package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AgentInvocationDeadlineTest {

    @Test
    void interruptsABlockingProviderAtTheApplicationDeadline() throws Exception {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID runId = UUID.randomUUID();
        Instant deadline = Instant.now().plusMillis(120);
        when(execution.budget(runId)).thenReturn(budget(deadline, null));
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentInvocationDeadline guard = new AgentInvocationDeadline(
                    execution, calls, Duration.ofMillis(20));

            assertThatThrownBy(() -> guard.invoke(runId, deadline, () -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofMinutes(5));
                        } catch (InterruptedException stopped) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                            stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.TIMEOUT));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptsABlockingProviderWhenTheRunOwnerCancels() throws Exception {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID runId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(10);
        when(execution.budget(runId))
                .thenReturn(budget(deadline, null))
                .thenReturn(budget(deadline, Instant.now()));
        CountDownLatch interrupted = new CountDownLatch(1);

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentInvocationDeadline guard = new AgentInvocationDeadline(
                    execution, calls, Duration.ofMillis(20));

            assertThatThrownBy(() -> guard.invoke(runId, deadline, () -> {
                        try {
                            Thread.sleep(Duration.ofMinutes(5));
                        } catch (InterruptedException stopped) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                            stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.CANCELLED));
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private BudgetSnapshot budget(Instant deadline, Instant cancellation) {
        return new BudgetSnapshot(24_000, 0, 0, 0, deadline, cancellation);
    }
}
