package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.TaskScheduler;

class TeachingTerminalRecoveryContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
            .withUserConfiguration(TeachingTerminalRecoveryScanConfiguration.class)
            .withBean(
                    "teachingTerminalRecoveryScheduler",
                    TaskScheduler.class,
                    () -> mock(TaskScheduler.class))
            .withBean("taskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void productionApiScanConstructsTerminalRecoveryWithItsQualifiedScheduler() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("api");
            assertThat(context).hasSingleBean(TeachingTerminalRecovery.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = TeachingTerminalRecovery.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = TeachingTerminalRecovery.class))
    static class TeachingTerminalRecoveryScanConfiguration {}
}
