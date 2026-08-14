package com.rulepilot.ingestion.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class ProcessingProgressRedisConfigurationTest {

    @Test
    void createsTheApiSideCrossRuntimeListenerContainer() {
        RedisConnectionFactory connections = mock(RedisConnectionFactory.class);

        var container = new ProcessingProgressRedisConfiguration()
                .processingProgressRedisMessageListenerContainer(connections);

        assertThat(container).extracting("connectionFactory").isSameAs(connections);
    }
}
