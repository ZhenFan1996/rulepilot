package com.rulepilot.ingestion.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
