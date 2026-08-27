package com.rulepilot.teaching.adapter.out.background;

import com.rulepilot.shared.AsyncContextPropagation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
class TeachingGenerationConfiguration {

    /**
     * Keeps ordinary scheduled infrastructure on an explicitly named default lane. Rabbit publisher confirms and
     * queue inspection may legitimately block; neither may consume the durable Teaching handoff lane.
     */
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("infrastructure-schedule-", 1);
    }

    /** Claims persisted READY handoffs independently from document messaging and monitoring schedules. */
    @Bean(name = "teachingHandoffScheduler")
    ThreadPoolTaskScheduler teachingHandoffScheduler(
            @Value("${rulepilot.teaching.import-handoff.scheduler-pool-size:1}") int poolSize) {
        if (poolSize < 1 || poolSize > 2) {
            throw new IllegalArgumentException("teaching handoff scheduler pool size must be between one and two");
        }
        return scheduler("teaching-handoff-", poolSize);
    }

    @Bean(name = "teachingStartupExecutor")
    ThreadPoolTaskExecutor teachingStartupExecutor(
            @Value("${rulepilot.teaching.startup.queue-capacity:8}") int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > 40) {
            throw new IllegalArgumentException("teaching startup queue capacity must be between one and forty");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("teaching-startup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }

    @Bean(name = "teachingGenerationExecutor")
    ThreadPoolTaskExecutor teachingGenerationExecutor(
            @Value("${rulepilot.teaching.background.core-pool-size:1}") int corePoolSize,
            @Value("${rulepilot.teaching.background.max-pool-size:3}") int maxPoolSize,
            @Value("${rulepilot.teaching.background.queue-capacity:20}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("teaching-generation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }

    /**
     * Keeps optional crop discovery from occupying the only lesson-generation worker on a small host. A player can
     * start the next text-first lesson while previously published chapters continue to gain verified illustrations.
     */
    @Bean(name = "visualEnrichmentExecutor")
    ThreadPoolTaskExecutor visualEnrichmentExecutor(
            @Value("${rulepilot.teaching.visual-enrichment.core-pool-size:1}") int corePoolSize,
            @Value("${rulepilot.teaching.visual-enrichment.max-pool-size:1}") int maxPoolSize,
            @Value("${rulepilot.teaching.visual-enrichment.queue-capacity:2}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("visual-enrichment-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }

    /**
     * Vision calls are deliberately isolated from base lesson generation. Each page request owns its source image,
     * provider response, validation, and persistence boundary. This pool serves post-publication positioning and
     * complete visual audits; Teaching page catalog calls own their bounded lane in the cataloger.
     * The zero-capacity queue still rejects work above the configured per-lesson bound.
     */
    @Bean(name = "visualLocationExecutor")
    ThreadPoolTaskExecutor visualLocationExecutor(
            @Value("${rulepilot.visual.request-parallelism:10}") int requestParallelism) {
        if (requestParallelism < 1 || requestParallelism > 10) {
            throw new IllegalArgumentException("visual request parallelism must be between one and ten");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(requestParallelism);
        executor.setMaxPoolSize(requestParallelism);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("visual-location-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }

    @Bean(name = "publicCoverWarmupExecutor")
    ThreadPoolTaskExecutor publicCoverWarmupExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(60);
        executor.setThreadNamePrefix("public-cover-warmup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix, int poolSize) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return scheduler;
    }
}
