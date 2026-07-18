package com.rulepilot.document.adapter.out.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.api-enabled", havingValue = "true", matchIfMissing = true)
public class RabbitDocumentQueueMetrics {

    private final RabbitAdmin rabbitAdmin;
    private final List<ObservedQueue> queues;

    public RabbitDocumentQueueMetrics(
            RabbitAdmin rabbitAdmin,
            MeterRegistry metrics,
            @Value("${rulepilot.document.messaging.queue}") String mainQueue,
            @Value("${rulepilot.document.messaging.dead-letter-queue}") String deadLetterQueue,
            @Value("${rulepilot.document.messaging.max-attempts}") int maxAttempts) {
        this.rabbitAdmin = rabbitAdmin;
        this.queues = observedQueues(mainQueue, deadLetterQueue, maxAttempts, metrics);
    }

    @Scheduled(fixedDelayString = "${rulepilot.document.messaging.metrics-fixed-delay}")
    public void refresh() {
        for (var queue : queues) {
            try {
                var information = rabbitAdmin.getQueueInfo(queue.name());
                if (information == null) {
                    throw new IllegalStateException("RabbitMQ queue does not exist");
                }
                queue.depth().set((double) information.getMessageCount());
            } catch (RuntimeException exception) {
                queue.depth().set(Double.NaN);
                queue.pollErrors().increment();
            }
        }
    }

    private List<ObservedQueue> observedQueues(
            String mainQueue,
            String deadLetterQueue,
            int maxAttempts,
            MeterRegistry metrics) {
        var observed = new ArrayList<ObservedQueue>();
        observed.add(register(mainQueue, "main", metrics));
        for (int attempt = 2; attempt <= maxAttempts; attempt++) {
            observed.add(register(mainQueue + ".retry." + attempt, "retry-" + attempt, metrics));
        }
        observed.add(register(deadLetterQueue, "dead-letter", metrics));
        return List.copyOf(observed);
    }

    private ObservedQueue register(String name, String role, MeterRegistry metrics) {
        var depth = new AtomicReference<>(Double.NaN);
        Gauge.builder("rulepilot.document.processing.queue.messages", depth, AtomicReference::get)
                .description("Ready messages in a document processing queue")
                .tag("queue", role)
                .register(metrics);
        Counter pollErrors = Counter.builder("rulepilot.document.processing.queue.poll.errors")
                .description("RabbitMQ queue depth polling failures")
                .tag("queue", role)
                .register(metrics);
        return new ObservedQueue(name, depth, pollErrors);
    }

    private record ObservedQueue(String name, AtomicReference<Double> depth, Counter pollErrors) {}
}
