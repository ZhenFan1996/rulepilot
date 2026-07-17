package com.rulepilottest;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.RulePilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = RulePilotApplication.class)
@ActiveProfiles("test")
class RulePilotApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationContextLoads() {
        assertThat(applicationContext.getBean(RulePilotApplication.class)).isNotNull();
    }
}
