package com.rulepilot.teaching.adapter.out.background;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TeachingGenerationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TeachingGenerationConfiguration.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void carriesTheLaunchingUserIntoBackgroundGeneration() throws InterruptedException {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("player", "n/a", java.util.List.of()));
        var executor = new TeachingGenerationConfiguration().teachingGenerationExecutor(1, 1, 1);
        var observedUser = new AtomicReference<String>();
        var completed = new CountDownLatch(1);
        executor.initialize();

        try {
            executor.execute(() -> {
                observedUser.set(SecurityContextHolder.getContext().getAuthentication().getName());
                completed.countDown();
            });

            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(observedUser).hasValue("player");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void startupLaneRunsWhileLongTailGenerationLaneIsOccupied() throws InterruptedException {
        var configuration = new TeachingGenerationConfiguration();
        var startup = configuration.teachingStartupExecutor(1);
        var continuation = configuration.teachingGenerationExecutor(1, 1, 1);
        var continuationStarted = new CountDownLatch(1);
        var releaseContinuation = new CountDownLatch(1);
        var startupCompleted = new CountDownLatch(1);
        startup.initialize();
        continuation.initialize();

        try {
            continuation.execute(() -> {
                continuationStarted.countDown();
                try {
                    releaseContinuation.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(continuationStarted.await(3, TimeUnit.SECONDS)).isTrue();

            startup.execute(startupCompleted::countDown);

            assertThat(startupCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseContinuation.countDown();
            startup.shutdown();
            continuation.shutdown();
        }
    }

    @Test
    void doesNotCreateTeachingExecutorsInThePdfWorkerRuntime() {
        contextRunner
                .withPropertyValues("rulepilot.runtime.api-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("teachingStartupExecutor");
                    assertThat(context).doesNotHaveBean("teachingGenerationExecutor");
                });
    }

    @Test
    void createsIndependentStartupAndContinuationExecutorsInTheApiRuntime() {
        contextRunner
                .withPropertyValues("rulepilot.runtime.api-enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("teachingStartupExecutor");
                    assertThat(context).hasBean("teachingGenerationExecutor");
                    assertThat(context.getBean("teachingStartupExecutor"))
                            .isNotSameAs(context.getBean("teachingGenerationExecutor"));
                });
    }
}
