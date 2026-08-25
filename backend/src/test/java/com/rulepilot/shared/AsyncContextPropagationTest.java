package com.rulepilot.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AsyncContextPropagationTest {

    private static final String ACCESSOR_KEY = AsyncContextPropagationTest.class.getName();
    private final ThreadLocal<String> observationContext = new ThreadLocal<>();

    @AfterEach
    void clearContext() {
        observationContext.remove();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ACCESSOR_KEY);
        SecurityContextHolder.clearContext();
    }

    @Test
    void taskDecoratorCarriesAndThenRestoresObservationAndAuthenticationContext() {
        registerAccessor();
        observationContext.set("trace-context");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("player-42", "unused"));
        AtomicReference<String> observed = new AtomicReference<>();
        Runnable decorated = AsyncContextPropagation.taskDecorator().decorate(() -> observed.set(
                observationContext.get() + ":" + SecurityContextHolder.getContext().getAuthentication().getName()));
        observationContext.remove();
        SecurityContextHolder.clearContext();

        decorated.run();

        assertThat(observed).hasValue("trace-context:player-42");
        assertThat(observationContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void executorServiceCarriesContextIntoApplicationOwnedWorkerThreads() throws Exception {
        registerAccessor();
        observationContext.set("trace-context");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("player-42", "unused"));
        var executor = AsyncContextPropagation.executorService(
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("context-test-").factory()));

        try {
            assertThat(executor.submit(() -> observationContext.get() + ":"
                            + SecurityContextHolder.getContext().getAuthentication().getName())
                    .get(2, TimeUnit.SECONDS))
                    .isEqualTo("trace-context:player-42");
        } finally {
            executor.shutdownNow();
        }
    }

    private void registerAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(ACCESSOR_KEY, observationContext);
    }
}
