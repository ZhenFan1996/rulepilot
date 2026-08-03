package com.rulepilot.assistant.adapter.out.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Supplies the Jackson 2 codec required by the native tool protocol on Spring Boot 4. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class NativeToolJsonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper nativeToolObjectMapper() {
        return new ObjectMapper();
    }
}
