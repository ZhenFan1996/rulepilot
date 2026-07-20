package com.rulepilot.teaching.adapter.out.background;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TeachingGenerationConfigurationTest {

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
}
