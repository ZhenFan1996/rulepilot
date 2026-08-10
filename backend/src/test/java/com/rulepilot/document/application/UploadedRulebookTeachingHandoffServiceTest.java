package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadedRulebookTeachingHandoffServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void normalizesAndPersistsThePlayerIntentBeforeDocumentProcessingCompletes() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        var snapshot = snapshot(handoffId, versionId, "先讲清开局。", UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
        when(store.request(any(UUID.class), eq(versionId), eq("alice"), eq("先讲清开局。"), eq(NOW)))
                .thenReturn(snapshot);
        when(documents.findReferences(List.of(versionId))).thenReturn(Map.of(
                versionId, new Reference(versionId, editionId, "SETI Rules", null, null)));
        var service = service(store, documents);

        var result = service.request(versionId, " 先讲清开局。 ", " alice ");

        assertThat(result.documentVersionId()).isEqualTo(versionId);
        assertThat(result.editionId()).isEqualTo(editionId);
        assertThat(result.rulebookTitle()).isEqualTo("SETI Rules");
        assertThat(result.state()).isEqualTo(UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
    }

    @Test
    void claimsOnlyTheDurableReadyWorkExposedByTheDocumentStore() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(store.claimReady(4, NOW)).thenReturn(List.of(snapshot(
                handoffId,
                versionId,
                null,
                UploadedRulebookTeachingHandoffStore.State.LAUNCHING)));
        var service = service(store, documents);

        assertThat(service.claimReady(4)).containsExactly(
                new com.rulepilot.document.UploadedRulebookTeachingHandoffs.ReadyHandoff(
                        handoffId, versionId, "alice", null));
    }

    @Test
    void recordsTheRealPreparationRunIdentity() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID handoffId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var service = service(store, documents);

        service.markLaunched(handoffId, runId);

        verify(store).completeLaunch(handoffId, runId, NOW);
    }

    @Test
    void terminalizesAWaitingHandoffWhenDocumentProcessingFailed() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        when(store.failUnusableDocuments(NOW)).thenReturn(2);
        var service = service(store, documents);

        assertThat(service.failUnusableDocuments()).isEqualTo(2);
    }

    private UploadedRulebookTeachingHandoffService service(
            UploadedRulebookTeachingHandoffStore store,
            RuleDocumentRepository documents) {
        return new UploadedRulebookTeachingHandoffService(
                store, documents, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UploadedRulebookTeachingHandoffStore.Snapshot snapshot(
            UUID handoffId,
            UUID versionId,
            String goal,
            UploadedRulebookTeachingHandoffStore.State state) {
        return new UploadedRulebookTeachingHandoffStore.Snapshot(
                handoffId,
                versionId,
                "alice",
                goal,
                state,
                null,
                null,
                NOW,
                NOW);
    }
}
