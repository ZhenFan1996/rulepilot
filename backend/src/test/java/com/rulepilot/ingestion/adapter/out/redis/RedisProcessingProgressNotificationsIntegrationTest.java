package com.rulepilot.ingestion.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisProcessingProgressNotificationsIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(30));

    @Test
    void deliversAWorkerPublicationToAnIndependentApiSubscriber() throws Exception {
        var connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        var redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        var listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connections);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        var subscriberBeans = new StaticListableBeanFactory();
        subscriberBeans.addBean("processingProgressRedisMessageListenerContainer", listenerContainer);
        var subscriber = new RedisProcessingProgressNotifications(
                redis, subscriberBeans.getBeanProvider(RedisMessageListenerContainer.class));
        var worker = new RedisProcessingProgressNotifications(
                redis, new StaticListableBeanFactory().getBeanProvider(RedisMessageListenerContainer.class));
        var received = new ArrayBlockingQueue<ProgressSnapshot>(1);
        var versionId = java.util.UUID.randomUUID();
        var snapshot = new ProgressSnapshot("READY", 100, 12, 12, true);
        Runnable unsubscribe = subscriber.subscribe(versionId, received::offer);

        try {
            worker.publish(versionId, snapshot);

            assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo(snapshot);
        } finally {
            unsubscribe.run();
            listenerContainer.stop();
            listenerContainer.destroy();
            connections.destroy();
        }
    }
}
