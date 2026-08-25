package com.rulepilot.document.adapter.out.messaging;

import com.rulepilot.shared.AsyncContextPropagation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
class DocumentOutboxWakeupConfiguration {

    @Bean(name = "documentOutboxWakeupExecutor")
    ThreadPoolTaskExecutor documentOutboxWakeupExecutor(
            @Value("${rulepilot.document.messaging.wakeup-queue-capacity:8}") int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > 40) {
            throw new IllegalArgumentException("document outbox wake-up queue capacity must be between one and forty");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("document-outbox-wakeup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }
}
