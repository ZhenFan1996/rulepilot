package com.rulepilot.shared;

import io.micrometer.context.ContextExecutorService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

/** Carries request observation and authentication context across application-owned async boundaries. */
public final class AsyncContextPropagation {

    private AsyncContextPropagation() {}

    public static TaskDecorator taskDecorator() {
        return new CompositeTaskDecorator(List.of(
                new ContextPropagatingTaskDecorator(),
                (TaskDecorator) DelegatingSecurityContextRunnable::new));
    }

    public static ExecutorService executorService(ExecutorService delegate) {
        return new DelegatingSecurityContextExecutorService(ContextExecutorService.wrap(delegate));
    }

    public static Runnable runnable(Runnable task) {
        return taskDecorator().decorate(task);
    }
}
