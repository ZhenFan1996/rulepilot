package com.rulepilot.ingestion.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.ingestion.application.ProcessingProgressTracker;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProcessingProgressControllerTest {

    @Test
    void subscribesOnlyToAnOwnedDocumentVersion() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "EXTRACTING", "player")));
        when(progress.subscribe(eq(versionId), any())).thenReturn(() -> {});
        when(progress.current(versionId)).thenReturn(Optional.empty());

        var emitter = controller.progress(versionId, () -> "player");

        assertThat(emitter).isNotNull();
    }

    @Test
    void removesAListenerWhenTerminalProgressArrivesDuringSubscriptionRegistration() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        Runnable removeListener = mock(Runnable.class);
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "INDEXING", "player")));
        when(progress.subscribe(eq(versionId), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ProcessingProgressTracker.ProgressSnapshot> listener =
                    invocation.getArgument(1);
            listener.accept(new ProcessingProgressTracker.ProgressSnapshot("READY", 100, 12, 12, true));
            return removeListener;
        });

        var emitter = controller.progress(versionId, () -> "player");

        assertThat(emitter).isNotNull();
        verify(removeListener).run();
    }

    @Test
    void hidesAnotherOwnersProgressAsNotFoundBeforeSubscribing() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "EXTRACTING", "other-player")));

        assertThatThrownBy(() -> controller.progress(versionId, () -> "player"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(progress);
    }

    @Test
    void hidesAMissingVersionAsNotFoundBeforeSubscribing() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.progress(versionId, () -> "player"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(progress);
    }

    @Test
    void reportsDurableReadyStateWhenCachedProgressIsStale() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "READY", "player")));
        when(progress.current(versionId)).thenReturn(Optional.of(
                new ProcessingProgressTracker.ProgressSnapshot("INDEXING", 95, 12, 12, false)));

        var snapshot = controller.snapshot(versionId, () -> "player");

        assertThat(snapshot)
                .extracting("stage", "percentage", "processedPages", "totalPages", "complete")
                .containsExactly("READY", 100, 12, 12, true);
    }

    @Test
    void reportsDurableTerminalStateWhenCachedProgressHasExpired() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "FAILED", "player")));
        when(progress.current(versionId)).thenReturn(Optional.empty());

        var snapshot = controller.snapshot(versionId, () -> "player");

        assertThat(snapshot)
                .extracting("stage", "percentage", "processedPages", "totalPages", "complete")
                .containsExactly("FAILED", 100, 0, 0, true);
    }

    @Test
    void reportsDurableActiveStageWhenCachedProgressHasExpired() {
        ProcessingProgressTracker progress = mock(ProcessingProgressTracker.class);
        DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
        ProcessingProgressController controller = new ProcessingProgressController(progress, versions);
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId))
                .thenReturn(Optional.of(new VersionScope(versionId, null, "EXTRACTING", "player")));
        when(progress.current(versionId)).thenReturn(Optional.empty());

        var snapshot = controller.snapshot(versionId, () -> "player");

        assertThat(snapshot)
                .extracting("stage", "percentage", "processedPages", "totalPages", "complete")
                .containsExactly("EXTRACTING", 0, 0, 0, false);
    }
}
