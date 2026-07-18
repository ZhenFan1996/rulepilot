package com.rulepilot.document.adapter.out.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("!test")
@EnableRabbit
@EnableScheduling
public class RabbitDocumentMessagingConfiguration {

    @Bean
    RabbitAdmin documentRabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    DirectExchange documentProcessingExchange(
            @Value("${rulepilot.document.messaging.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    Queue documentProcessingQueue(@Value("${rulepilot.document.messaging.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    Binding documentProcessingBinding(
            Queue documentProcessingQueue,
            DirectExchange documentProcessingExchange,
            @Value("${rulepilot.document.messaging.routing-key}") String routingKey) {
        return BindingBuilder.bind(documentProcessingQueue).to(documentProcessingExchange).with(routingKey);
    }

    @Bean
    Declarables documentProcessingRetryTopology(
            @Value("${rulepilot.document.messaging.exchange}") String mainExchange,
            @Value("${rulepilot.document.messaging.queue}") String mainQueue,
            @Value("${rulepilot.document.messaging.routing-key}") String mainRoutingKey,
            @Value("${rulepilot.document.messaging.retry-exchange}") String retryExchangeName,
            @Value("${rulepilot.document.messaging.dead-letter-exchange}") String deadLetterExchangeName,
            @Value("${rulepilot.document.messaging.dead-letter-queue}") String deadLetterQueueName,
            @Value("${rulepilot.document.messaging.max-attempts}") int maxAttempts,
            @Value("${rulepilot.document.messaging.retry-delays}") List<Duration> retryDelays) {
        if (maxAttempts < 2 || retryDelays.size() != maxAttempts - 1) {
            throw new IllegalArgumentException("retry delays must define one delay for every retry attempt");
        }
        var declarables = new ArrayList<org.springframework.amqp.core.Declarable>();
        var retryExchange = new DirectExchange(retryExchangeName, true, false);
        var deadLetterExchange = new DirectExchange(deadLetterExchangeName, true, false);
        var deadLetterQueue = new Queue(deadLetterQueueName, true);
        declarables.add(retryExchange);
        declarables.add(deadLetterExchange);
        declarables.add(deadLetterQueue);
        declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(mainRoutingKey));
        for (int index = 0; index < retryDelays.size(); index++) {
            int attempt = index + 2;
            var arguments = new HashMap<String, Object>();
            arguments.put("x-message-ttl", retryDelays.get(index).toMillis());
            arguments.put("x-dead-letter-exchange", mainExchange);
            arguments.put("x-dead-letter-routing-key", mainRoutingKey);
            var retryQueue = new Queue(mainQueue + ".retry." + attempt, true, false, false, arguments);
            declarables.add(retryQueue);
            declarables.add(BindingBuilder.bind(retryQueue)
                    .to(retryExchange)
                    .with(mainRoutingKey + ".retry." + attempt));
        }
        return new Declarables(declarables);
    }
}
