package com.rulepilot.teaching.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import com.rulepilot.teaching.application.ImportedRulebookTeachingLauncher;
import com.rulepilot.teaching.application.UploadedRulebookTeachingLauncher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

class DocumentReadyTeachingHandoffListenerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00.250Z");

    @Test
    void immediatelyDispatchesBothPersistedHandoffTypesAndRecordsWakeupLag() {
        ImportedRulebookTeachingLauncher imported = mock(ImportedRulebookTeachingLauncher.class);
        UploadedRulebookTeachingLauncher uploaded = mock(UploadedRulebookTeachingLauncher.class);
        var metrics = new SimpleMeterRegistry();
        var listener = new DocumentReadyTeachingHandoffListener(
                imported, uploaded, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

        UUID versionId = UUID.randomUUID();
        listener.onReady(message(
                """
                {"schemaVersion":1,"documentVersionId":"%s","readyAt":"2026-08-13T08:00:00Z"}
                """.formatted(versionId)));

        verify(imported).dispatchReadyHandoffs(versionId);
        verify(uploaded).dispatchReadyHandoffs(versionId);
        assertThat(metrics.timer("rulepilot.teaching.handoff.ready_to_dispatch").count()).isEqualTo(1);
        assertThat(metrics.timer("rulepilot.teaching.handoff.ready_to_dispatch").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(250);
        assertThat(metrics.counter("rulepilot.teaching.handoff.wakeup", "outcome", "dispatched").count())
                .isEqualTo(1);
    }

    @Test
    void rejectsMalformedWakeupsWithoutTouchingPersistedHandoffs() {
        ImportedRulebookTeachingLauncher imported = mock(ImportedRulebookTeachingLauncher.class);
        UploadedRulebookTeachingLauncher uploaded = mock(UploadedRulebookTeachingLauncher.class);
        var listener = new DocumentReadyTeachingHandoffListener(
                imported,
                uploaded,
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> listener.onReady(message(
                        "{\"schemaVersion\":2,\"documentVersionId\":\"not-a-uuid\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(imported, uploaded);
    }

    @Test
    void acknowledgesDispatchFailureForScheduledDatabaseRecoveryAndStillTriesBothHandoffTypes() {
        ImportedRulebookTeachingLauncher imported = mock(ImportedRulebookTeachingLauncher.class);
        UploadedRulebookTeachingLauncher uploaded = mock(UploadedRulebookTeachingLauncher.class);
        var metrics = new SimpleMeterRegistry();
        UUID versionId = UUID.randomUUID();
        doThrow(new IllegalStateException("database unavailable"))
                .when(imported)
                .dispatchReadyHandoffs(versionId);
        var listener = new DocumentReadyTeachingHandoffListener(
                imported, uploaded, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

        listener.onReady(message(
                """
                {"schemaVersion":1,"documentVersionId":"%s","readyAt":"2026-08-13T08:00:00Z"}
                """.formatted(versionId)));

        verify(imported).dispatchReadyHandoffs(versionId);
        verify(uploaded).dispatchReadyHandoffs(versionId);
        assertThat(metrics.counter(
                                "rulepilot.teaching.handoff.wakeup",
                                "outcome",
                                "fallback_required")
                        .count())
                .isEqualTo(1);
    }

    private Message message(String payload) {
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8));
        message.getMessageProperties().setHeader("rulepilot-event-type", "DocumentReady");
        return message;
    }
}
