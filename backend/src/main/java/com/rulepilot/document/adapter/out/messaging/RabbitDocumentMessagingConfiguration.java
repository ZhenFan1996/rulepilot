package com.rulepilot.document.adapter.out.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
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
}
