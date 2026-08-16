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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentInvocationDeadlineTest {

    @Test
    void interruptsABlockingProviderAtTheApplicationDeadline() throws Exception {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID runId = UUID.randomUUID();
        Instant deadline = Instant.now().plusMillis(120);
        when(execution.budget(runId)).thenReturn(budget(deadline, null));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentInvocationDeadline guard = new AgentInvocationDeadline(
                    execution, calls, Duration.ofMillis(20));

            assertThatThrownBy(() -> guard.invoke(runId, deadline, () -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofMinutes(5));
                        } catch (InterruptedException stopped) {
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                            stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.TIMEOUT));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
        }
    }

    @Test
    void interruptsABlockingProviderWhenTheRunOwnerCancels() {
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID runId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(10);
        when(execution.budget(runId))
                .thenReturn(budget(deadline, null))
                .thenReturn(budget(deadline, Instant.now()));
        AtomicBoolean interrupted = new AtomicBoolean();

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentInvocationDeadline guard = new AgentInvocationDeadline(
                    execution, calls, Duration.ofMillis(20));

            assertThatThrownBy(() -> guard.invoke(runId, deadline, () -> {
                        try {
                            Thread.sleep(Duration.ofMinutes(5));
                        } catch (InterruptedException stopped) {
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOfSatisfying(AgentExecutionStoppedException.class,
                            stopped -> assertThat(stopped.reason()).isEqualTo(StopReason.CANCELLED));
            assertThat(interrupted).isTrue();
        }
    }

    private BudgetSnapshot budget(Instant deadline, Instant cancellation) {
        return new BudgetSnapshot(40, 24, 16, 24_000, 0, 0, 0, deadline, cancellation);
    }
}
