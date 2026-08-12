package com.rulepilot.teaching.adapter.out.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
class TeachingGenerationConfiguration {

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
        executor.setTaskDecorator(DelegatingSecurityContextRunnable::new);
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
        executor.setTaskDecorator(DelegatingSecurityContextRunnable::new);
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
        executor.setTaskDecorator(DelegatingSecurityContextRunnable::new);
        return executor;
    }

    /**
     * Vision calls are deliberately isolated from base lesson generation. A tiny configurable concurrency lets a
     * player receive independent icon/component crops sooner, while the zero-capacity queue still rejects excess work
     * instead of accumulating costly provider calls.
     */
    @Bean(name = "visualLocationExecutor")
    ThreadPoolTaskExecutor visualLocationExecutor(
            @Value("${rulepilot.visual.request-parallelism:1}") int requestParallelism) {
        if (requestParallelism < 1 || requestParallelism > 3) {
            throw new IllegalArgumentException("visual request parallelism must be between one and three");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(requestParallelism);
        executor.setMaxPoolSize(requestParallelism);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("visual-location-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
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
        return executor;
    }
}
