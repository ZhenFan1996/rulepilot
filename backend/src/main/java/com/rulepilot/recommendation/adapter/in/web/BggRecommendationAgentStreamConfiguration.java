package com.rulepilot.recommendation.adapter.in.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class BggRecommendationAgentStreamConfiguration {

    @Bean(name = "bggRecommendationStreamExecutor")
    ThreadPoolTaskExecutor bggRecommendationStreamExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("bgg-recommendation-stream-");
        executor.initialize();
        return executor;
    }
}
