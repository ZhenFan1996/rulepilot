package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.adapter.out.messaging.RabbitDocumentReadyNotificationPublisher;
import com.rulepilot.ingestion.adapter.in.messaging.DocumentProcessingWorker;
import com.rulepilot.teaching.adapter.in.messaging.DocumentReadyTeachingHandoffListener;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class TeachingRuntimeOwnershipTest {

    @Test
    void durableTeachingHandoffsAreClaimedOnlyByTheApiRuntime() {
        assertApiOwned(ImportedRulebookTeachingLauncher.class);
        assertApiOwned(UploadedRulebookTeachingLauncher.class);
        assertApiOwned(DocumentReadyTeachingHandoffListener.class);
        assertApiOwned(PublicCoverThumbnailWarmup.class);
    }

    @Test
    void readyWakeupsArePublishedOnlyByThePdfWorkerRuntime() {
        assertWorkerOwned(DocumentProcessingWorker.class);
        assertWorkerOwned(RabbitDocumentReadyNotificationPublisher.class);
    }

    @Test
    void durableTeachingHandoffsUseTheirDedicatedScheduler() throws NoSuchMethodException {
        assertDedicatedScheduler(ImportedRulebookTeachingLauncher.class);
        assertDedicatedScheduler(UploadedRulebookTeachingLauncher.class);
    }

    private void assertApiOwned(Class<?> launcherType) {
        var condition = launcherType.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("rulepilot.runtime.api-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    private void assertDedicatedScheduler(Class<?> launcherType) throws NoSuchMethodException {
        Method launch = launcherType.getDeclaredMethod("launchReadyHandoffs");
        Scheduled scheduled = launch.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("teachingHandoffScheduler");
    }

    private void assertWorkerOwned(Class<?> workerType) {
        var condition = workerType.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("rulepilot.runtime.worker-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
