package com.rulepilot.assistant.adapter.out.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class NativeToolJsonConfigurationTest {

    @Test
    void suppliesTheNativeToolCodecOutsideTheTestProfile() {
        try (var context = new AnnotationConfigApplicationContext(NativeToolJsonConfiguration.class)) {
            assertThat(context.getBean(ObjectMapper.class)).isNotNull();
        }
    }
}
