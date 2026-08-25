package com.rulepilot.document.adapter.out.source;

import com.rulepilot.shared.AsyncContextPropagation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
class OfficialRulebookImportConfiguration {

    @Bean(name = "officialRulebookImportExecutor")
    ThreadPoolTaskExecutor officialRulebookImportExecutor(
            @Value("${rulepilot.rulebook-import.queue-capacity:8}") int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > 40) {
            throw new IllegalArgumentException("official rulebook import queue capacity must be between 1 and 40");
        }
        var executor = new ThreadPoolTaskExecutor();
        // The old 1-core/2-max setup queued ordinary second imports and only used the second worker after the queue
        // filled. Two workers keep the already-approved peak concurrency while removing that avoidable queue wait.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("official-rulebook-import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }
}
