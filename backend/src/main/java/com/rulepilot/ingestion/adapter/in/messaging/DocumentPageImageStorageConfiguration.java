package com.rulepilot.ingestion.adapter.in.messaging;

import com.rulepilot.shared.AsyncContextPropagation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
class DocumentPageImageStorageConfiguration {

    @Bean(name = "documentPageImageStorageExecutor", destroyMethod = "shutdown")
    ExecutorService documentPageImageStorageExecutor(
            @Value("${rulepilot.document.page-image-storage-parallelism:2}") int parallelism) {
        if (parallelism < 1 || parallelism > 4) {
            throw new IllegalArgumentException("page image storage parallelism must be between one and four");
        }
        return AsyncContextPropagation.executorService(new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().name("document-page-image-store-", 0).factory(),
                (task, executor) -> {
                    try {
                        while (!executor.isShutdown()) {
                            if (executor.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) {
                                return;
                            }
                        }
                        throw new RejectedExecutionException("page image storage lane is shutting down");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException("page image storage submission was interrupted", interrupted);
                    }
                }));
    }
}
