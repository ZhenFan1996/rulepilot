package com.rulepilot.catalog.adapter.out.bgg;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BggCacheConfigurationTest {

    @Test
    void reservesWorkerDatabaseCapacityFromBackgroundCacheRefreshes() {
        ThreadPoolTaskExecutor executor = new BggCacheConfiguration().bggCacheRefreshExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
    }

    @Test
    void createsThePopularCatalogPrewarmExecutorOnlyInTheWorkerRuntime() throws Exception {
        Method factory = BggCacheConfiguration.class.getDeclaredMethod("bggPopularPrewarmExecutor");
        ConditionalOnProperty ownership = factory.getAnnotation(ConditionalOnProperty.class);

        assertThat(ownership).isNotNull();
        assertThat(ownership.name()).containsExactly("rulepilot.runtime.worker-enabled");
        assertThat(ownership.havingValue()).isEqualTo("true");
        assertThat(ownership.matchIfMissing()).isFalse();
    }
}
