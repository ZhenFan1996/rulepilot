package com.rulepilot.teaching.adapter.out.background;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
class TeachingGenerationConfiguration {

    @Bean(name = "teachingGenerationExecutor")
    ThreadPoolTaskExecutor teachingGenerationExecutor(
            @Value("${rulepilot.teaching.background.core-pool-size:2}") int corePoolSize,
            @Value("${rulepilot.teaching.background.max-pool-size:4}") int maxPoolSize,
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
}
