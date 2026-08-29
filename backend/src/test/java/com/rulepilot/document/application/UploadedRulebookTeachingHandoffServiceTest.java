package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness.ReuseAssessment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class UploadedRulebookTeachingHandoffServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void productionSpringContextSelectsTheInjectableConstructorWhenATestClockConstructorAlsoExists() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    UploadedRulebookTeachingHandoffStore.class,
                    () -> mock(UploadedRulebookTeachingHandoffStore.class));
            context.registerBean(RuleDocumentRepository.class, () -> mock(RuleDocumentRepository.class));
            context.registerBean(
                    RulebookTeachingEvidenceFreshness.class,
                    RulebookTeachingEvidenceFreshness::alwaysCurrent);
            context.register(UploadedRulebookTeachingHandoffService.class);

            context.refresh();

            assertThat(context.getBean(UploadedRulebookTeachingHandoffService.class)).isNotNull();
        }
    }

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
        when(store.claimReadyForDocument(versionId, 4, NOW)).thenReturn(List.of(snapshot(
                handoffId,
                versionId,
                null,
                UploadedRulebookTeachingHandoffStore.State.LAUNCHING)));
        var service = service(store, documents);

        assertThat(service.claimReadyForDocument(versionId, 4)).containsExactly(
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

    @Test
    void retriesTheSameUploadedHandoffWithTheObservedPreparationRunIdentity() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        var waiting = snapshot(
                handoffId, versionId, null, UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
        when(store.findOwned(handoffId, "alice")).thenReturn(java.util.Optional.of(new UploadedRulebookTeachingHandoffStore.Snapshot(
                handoffId,
                versionId,
                "alice",
                null,
                UploadedRulebookTeachingHandoffStore.State.LAUNCHED,
                failedRunId,
                null,
                0,
                NOW,
                NOW)));
        when(store.retry(handoffId, failedRunId, "alice", NOW)).thenReturn(waiting);
        when(documents.findReferences(List.of(versionId))).thenReturn(Map.of(
                versionId, new Reference(versionId, editionId, "SETI Rules", null, null)));
        var service = service(store, documents);

        var retried = service.retry(handoffId, failedRunId, " alice ");

        assertThat(retried.id()).isEqualTo(handoffId);
        assertThat(retried.state()).isEqualTo(UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT);
        verify(store).retry(handoffId, failedRunId, "alice", NOW);
    }

    @Test
    void exposesATransientUploadedPreparationForTypedManualRetryWithoutLaunchingAReplacement() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        when(store.findUnreconciledLaunched(4)).thenReturn(List.of(
                new UploadedRulebookTeachingHandoffStore.RecoveryCandidate(
                        handoffId, versionId, "alice", failedRunId, 1)));
        when(freshness.assess(versionId, failedRunId, "alice"))
                .thenReturn(ReuseAssessment.RETRYABLE_FAILURE);
        when(store.failTerminal(
                        handoffId,
                        failedRunId,
                        "TEACHING_PREPARATION_FAILED",
                        NOW))
                .thenReturn(true);
        var service = new UploadedRulebookTeachingHandoffService(
                store, documents, freshness, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.failed()).isOne();
        verify(store).failTerminal(
                handoffId,
                failedRunId,
                "TEACHING_PREPARATION_FAILED",
                NOW);
    }

    @Test
    void settlesUploadedStorageFailureWithoutAutomaticTeachingRetry() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        when(store.findUnreconciledLaunched(4)).thenReturn(List.of(
                new UploadedRulebookTeachingHandoffStore.RecoveryCandidate(
                        handoffId, versionId, "alice", failedRunId, 0)));
        when(freshness.assess(versionId, failedRunId, "alice"))
                .thenReturn(ReuseAssessment.EXTERNAL_REPAIR_REQUIRED);
        when(store.failTerminal(
                        handoffId,
                        failedRunId,
                        "TEACHING_PREPARATION_STORAGE_FAILED",
                        NOW))
                .thenReturn(true);
        var service = new UploadedRulebookTeachingHandoffService(
                store, documents, freshness, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.failed()).isOne();
        verify(store).failTerminal(
                handoffId,
                failedRunId,
                "TEACHING_PREPARATION_STORAGE_FAILED",
                NOW);
    }

    @Test
    void removesACancelledUploadedIntentInsteadOfResurrectingIt() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cancelledRunId = UUID.randomUUID();
        when(store.findUnreconciledLaunched(4)).thenReturn(List.of(
                new UploadedRulebookTeachingHandoffStore.RecoveryCandidate(
                        handoffId, versionId, "alice", cancelledRunId, 0)));
        when(freshness.assess(versionId, cancelledRunId, "alice"))
                .thenReturn(ReuseAssessment.CANCELLED);
        when(store.dismissCancelled(handoffId, cancelledRunId)).thenReturn(true);
        var service = new UploadedRulebookTeachingHandoffService(
                store, documents, freshness, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.settled()).isOne();
        verify(store).dismissCancelled(handoffId, cancelledRunId);
    }

    @Test
    void exposesThePersistedAutomaticRecoveryCountAfterRefresh() {
        UploadedRulebookTeachingHandoffStore store = mock(UploadedRulebookTeachingHandoffStore.class);
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        var recovered = new UploadedRulebookTeachingHandoffStore.Snapshot(
                handoffId,
                versionId,
                "alice",
                null,
                UploadedRulebookTeachingHandoffStore.State.LAUNCHED,
                UUID.randomUUID(),
                null,
                1,
                NOW,
                NOW);
        when(store.findRecentOwned("alice", 20)).thenReturn(List.of(recovered));
        when(documents.findReferences(List.of(versionId))).thenReturn(Map.of(
                versionId, new Reference(versionId, editionId, "SETI Rules", null, null)));

        var recent = service(store, documents).recentOwned("alice");

        assertThat(recent).singleElement().satisfies(item ->
                assertThat(item.automaticRecoveryCount()).isOne());
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
                0,
                NOW,
                NOW);
    }
}
