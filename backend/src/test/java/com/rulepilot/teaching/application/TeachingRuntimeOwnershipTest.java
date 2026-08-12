package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class TeachingRuntimeOwnershipTest {

    @Test
    void durableTeachingHandoffsAreClaimedOnlyByTheApiRuntime() {
        assertApiOwned(ImportedRulebookTeachingLauncher.class);
        assertApiOwned(UploadedRulebookTeachingLauncher.class);
        assertApiOwned(PublicCoverThumbnailWarmup.class);
    }

    private void assertApiOwned(Class<?> launcherType) {
        var condition = launcherType.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("rulepilot.runtime.api-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
