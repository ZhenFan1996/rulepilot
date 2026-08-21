package com.rulepilot.catalog.adapter.out.bgg;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Profile("!test")
class BggCacheConfiguration {

    @Bean(name = "bggCacheRefreshExecutor")
    ThreadPoolTaskExecutor bggCacheRefreshExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("bgg-cache-refresh-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "bggPopularPrewarmExecutor")
    @ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true")
    ThreadPoolTaskExecutor bggPopularPrewarmExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("bgg-popular-prewarm-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "bggCoverPrewarmExecutor")
    @ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true")
    ThreadPoolTaskExecutor bggCoverPrewarmExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("bgg-cover-prewarm-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(40);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
