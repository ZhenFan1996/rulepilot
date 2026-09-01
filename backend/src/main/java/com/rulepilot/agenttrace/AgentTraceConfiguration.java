package com.rulepilot.agenttrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rulepilot.private-agent-trace.enabled", havingValue = "true")
@EnableConfigurationProperties(PrivateAgentTraceProperties.class)
class AgentTraceConfiguration {

    @Bean
    AgentTracePayloadCipher privateAgentTracePayloadCipher(PrivateAgentTraceProperties properties) {
        properties.validate();
        return new AesGcmAgentTracePayloadCipher(
                properties.getEncryptionKey(), properties.getEncryptionKeyVersion());
    }

    @Bean
    PrivateAgentTraceStore privateAgentTraceStore(
            StringRedisTemplate redis,
            ObjectMapper json,
            AgentTracePayloadCipher cipher,
            PrivateAgentTraceProperties properties) {
        return new RedisPrivateAgentTraceStore(redis, json, cipher, properties);
    }

    @Bean
    PrivateAgentTraceService privateAgentTraceService(
            PrivateAgentTraceStore store, PrivateAgentTraceProperties properties) {
        return new PrivateAgentTraceService(store, properties, Clock.systemUTC());
    }

    @Bean
    AgentTraceExporter privateAgentTraceExporter(ObjectMapper json) {
        return new AgentTraceExporter(json);
    }
}
