package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentProcessingStage;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DocumentProcessingServiceTest {

    @Test
    void keepsEachObjectStorePageImageReadBounded() {
        RuleDocumentRepository repository = Mockito.mock(RuleDocumentRepository.class);
        DocumentStorage storage = Mockito.mock(DocumentStorage.class);
        DocumentProcessingService service = new DocumentProcessingService(repository, storage);
        UUID versionId = UUID.randomUUID();
        DocumentVersion readyVersion = new DocumentVersion(
                versionId,
                UUID.randomUUID(),
                1,
                "rules.pdf",
                "documents/rules.pdf",
                "a".repeat(64),
                42,
                "application/pdf",
                ProcessingStatus.READY,
                Instant.parse("2026-08-12T00:00:00Z"));
        when(repository.findVersion(versionId)).thenReturn(Optional.of(readyVersion));
        when(repository.findPageImages(versionId, Set.of(1, 2, 3, 4, 5))).thenReturn(java.util.List.of());

        assertThat(service.read(versionId, Set.of(1, 2, 3, 4, 5))).isEmpty();
        assertThatThrownBy(() -> service.read(versionId, Set.of(1, 2, 3, 4, 5, 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested document page images are invalid");
        assertThat(DocumentPageImages.MAX_PAGES_PER_READ).isEqualTo(5);
    }

    @Test
    void resumesAChunkRetryFromTheExistingStructuredSource() {
        RuleDocumentRepository repository = Mockito.mock(RuleDocumentRepository.class);
        DocumentStorage storage = Mockito.mock(DocumentStorage.class);
        DocumentProcessingService service = new DocumentProcessingService(repository, storage);
        UUID versionId = UUID.randomUUID();
        DocumentVersion failedVersion = new DocumentVersion(
                versionId,
                UUID.randomUUID(),
                1,
                "rules.pdf",
                "documents/rules.pdf",
                "a".repeat(64),
                42,
                "application/pdf",
                ProcessingStatus.FAILED,
                Instant.parse("2026-07-25T00:00:00Z"));
        when(repository.findVersion(versionId)).thenReturn(Optional.of(failedVersion));

        service.prepareRetry(versionId, DocumentProcessingStage.CHUNK);

        ArgumentCaptor<DocumentVersion> updated = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(repository).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(ProcessingStatus.STRUCTURING);
    }
}
