package com.rulepilot.assistant.adapter.in.web;

import com.rulepilot.shared.AsyncContextPropagation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Profile("!test")
class StructuredRuleAnswerStreamConfiguration {

    @Bean(name = "structuredRuleAnswerStreamExecutor")
    ThreadPoolTaskExecutor structuredRuleAnswerStreamExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("structured-answer-stream-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncContextPropagation.taskDecorator());
        return executor;
    }
}
