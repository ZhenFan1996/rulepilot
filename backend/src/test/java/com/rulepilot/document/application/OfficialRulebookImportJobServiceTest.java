package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.catalog.CatalogEditionLanguageConfirmation;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness.ReuseAssessment;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoff;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoffState;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class OfficialRulebookImportJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String SOURCE = "https://publisher.example/rules.pdf";
    private static final UUID GAME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void persistsRealDownloadStagesAndCompletesWithTheUploadedVersion() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        UUID versionId = UUID.randomUUID();
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(1_024L);
            progress.downloaded(512, 1_024L);
            progress.downloaded(1_024, 1_024L);
            progress.downloadCompleted();
            progress.verifying();
            progress.saving();
            return uploadResult(versionId);
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var completed = service.requireOwned(launch.job().id(), "alice");

        assertThat(launch.reused()).isFalse();
        assertThat(completed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.COMPLETED);
        assertThat(completed.downloadedBytes()).isEqualTo(1_024);
        assertThat(completed.totalBytes()).isEqualTo(1_024);
        assertThat(completed.downloadCompletedAt()).isEqualTo(NOW);
        assertThat(completed.documentVersionId()).isEqualTo(versionId);
        assertThat(jobs.stages).containsExactly(
                OfficialRulebookImportJob.Stage.CONNECTING,
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                OfficialRulebookImportJob.Stage.SAVING,
                OfficialRulebookImportJob.Stage.COMPLETED);
    }

    @Test
    void bindsTheImportJobAndCompletedDocumentIntoOnePrivateJourneyTrace() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        UUID versionId = UUID.randomUUID();
        when(imports.importRulebook(any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any()))
                .thenReturn(uploadResult(versionId));
        CaptureHandle capture = mock(CaptureHandle.class);
        when(capture.enabled()).thenReturn(true);
        when(capture.bind(any(ResourceRef.class))).thenReturn(true);
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice", capture);

        ArgumentCaptor<ResourceRef> boundResources = ArgumentCaptor.forClass(ResourceRef.class);
        verify(capture, times(2)).bind(boundResources.capture());
        assertThat(boundResources.getAllValues())
                .extracting(ResourceRef::type)
                .containsExactly(ResourceType.IMPORT_JOB, ResourceType.DOCUMENT_VERSION);
        assertThat(boundResources.getAllValues().getFirst().id()).isEqualTo(launch.job().id());
        assertThat(boundResources.getAllValues().getLast().id()).isEqualTo(versionId);

        ArgumentCaptor<BindingOrFailure> events = ArgumentCaptor.forClass(BindingOrFailure.class);
        verify(capture, times(2)).bindingOrFailure(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event ->
                assertThat(event.context().stage()).isEqualTo(JourneyStage.IMPORT));
        assertThat(events.getAllValues().getFirst()).satisfies(event -> {
            assertThat(event.code()).isEqualTo("IMPORT_JOB_BOUND");
            assertThat(event.childResource()).isEqualTo(new ResourceRef(ResourceType.IMPORT_JOB, launch.job().id()));
        });
        assertThat(events.getAllValues().getLast()).satisfies(event -> {
            assertThat(event.code()).isEqualTo("DOCUMENT_VERSION_BOUND");
            assertThat(event.context().operationId()).isEqualTo(versionId);
            assertThat(event.context().parentOperationId()).isEqualTo(launch.job().id());
            assertThat(event.parentResource()).isEqualTo(new ResourceRef(ResourceType.IMPORT_JOB, launch.job().id()));
            assertThat(event.childResource()).isEqualTo(new ResourceRef(ResourceType.DOCUMENT_VERSION, versionId));
        });
    }

    @Test
    void coalescesFastDownloadProgressInsteadOfMakingTheNetworkWaitForEveryDatabaseWrite() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        long totalBytes = 20L * 1024 * 1024;
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(totalBytes);
            for (long downloaded = 256 * 1024; downloaded <= totalBytes; downloaded += 256 * 1024) {
                progress.downloaded(downloaded, totalBytes);
            }
            progress.downloadCompleted();
            progress.verifying();
            progress.saving();
            return uploadResult(UUID.randomUUID());
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");

        long downloadWrites = jobs.stages.stream()
                .filter(stage -> stage == OfficialRulebookImportJob.Stage.DOWNLOADING)
                .count();
        assertThat(downloadWrites).isBetween(2L, 7L);
        assertThat(service.requireOwned(launch.job().id(), "alice")).satisfies(completed -> {
            assertThat(completed.downloadedBytes()).isEqualTo(totalBytes);
            assertThat(completed.totalBytes()).isEqualTo(totalBytes);
            assertThat(completed.downloadCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void stillPersistsSlowDownloadProgressAtLeastOncePerSecond() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        MutableClock clock = new MutableClock(NOW);
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(512L * 1024);
            progress.downloaded(256L * 1024, 512L * 1024);
            assertThat(jobs.downloadWriteCount()).isEqualTo(1);
            clock.advance(Duration.ofMillis(1_100));
            progress.downloaded(512L * 1024, 512L * 1024);
            assertThat(jobs.downloadWriteCount()).isEqualTo(2);
            progress.downloadCompleted();
            progress.verifying();
            progress.saving();
            return uploadResult(UUID.randomUUID());
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                imports,
                Runnable::run,
                (editionId, language) -> false,
                catalog(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        GAME_ID,
                        "Opaque Edition",
                        "en"),
                clock);

        var launch = service.enqueue(command(), "alice");

        assertThat(jobs.downloadWriteCount()).isEqualTo(2);
        assertThat(service.requireOwned(launch.job().id(), "alice").downloadedBytes())
                .isEqualTo(512L * 1024);
    }

    @Test
    void reusesAnActiveDownloadForTheSameOwnerAndSource() {
        FakeJobs jobs = new FakeJobs();
        var active = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(active);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(command(), "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.job().id()).isEqualTo(active.id());
        verify(executor, never()).execute(any());
        verify(imports, never()).importRulebook(any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void persistsTheAutomaticTeachingIntentBeforeStartingTheDownloadWorker() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.job().teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(launch.job().teachingHandoff().learningGoal()).isEqualTo("重点讲清开局和第一轮。");
        verify(executor).execute(any());
    }

    @Test
    void confirmsOnlyAnExplicitlyReviewedCanonicalSourceLanguageAgainstTheBoundEdition() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        CatalogEditionLanguageConfirmation languages = mock(CatalogEditionLanguageConfirmation.class);
        UUID editionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OfficialRulebookImportJobService service = service(
                jobs, imports, executor, languages, catalog(editionId, GAME_ID, "Opaque Edition", "und"));
        var command = new OfficialRulebookImportJobService.Command(
                editionId,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                null,
                new OfficialRulebookImportIdentity.SourceClaim(
                        editionId, "Opaque Edition", "zh_cn", true),
                true);

        service.enqueue(command, "alice");

        verify(languages).confirmIfUnknown(editionId, "zh-CN");
        verify(executor).execute(any());
    }

    @Test
    void requiresConfirmationInsteadOfCollapsingUnknownEditionAndLanguageIntoTheCatalog() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        CatalogEditionLanguageConfirmation languages = mock(CatalogEditionLanguageConfirmation.class);
        UUID editionId = UUID.randomUUID();
        OfficialRulebookImportJobService service = service(
                jobs, imports, executor, languages, catalog(editionId, GAME_ID, "Opaque Edition", "und"));
        var command = new OfficialRulebookImportJobService.Command(
                editionId,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                null,
                new OfficialRulebookImportIdentity.SourceClaim(editionId, null, null, false),
                false);

        assertThatThrownBy(() -> service.enqueue(command, "alice"))
                .isInstanceOfSatisfying(OfficialRulebookImportIdentityException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            OfficialRulebookImportIdentityException.Code.CONFIRMATION_REQUIRED);
                    assertThat(failure.review().issues()).contains(
                            OfficialRulebookImportIdentity.Issue.SOURCE_EDITION_UNKNOWN,
                            OfficialRulebookImportIdentity.Issue.CATALOG_LANGUAGE_UNKNOWN,
                            OfficialRulebookImportIdentity.Issue.SOURCE_LANGUAGE_UNKNOWN);
                });
        verifyNoInteractions(languages, executor, imports);
    }

    @Test
    void rejectsAHumanLanguageLabelThatWasNotAVerifiedLanguageTag() {
        UUID editionId = UUID.randomUUID();
        OfficialRulebookImportJobService service = service(
                new FakeJobs(),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                mock(CatalogEditionLanguageConfirmation.class),
                catalog(editionId, GAME_ID, "Opaque Edition", "en"));
        assertThatThrownBy(() -> service.enqueue(
                        new OfficialRulebookImportJobService.Command(
                                editionId,
                                "Example Rules",
                                DocumentSourceType.BASE_RULEBOOK,
                                SOURCE,
                                true,
                                true,
                                null,
                                new OfficialRulebookImportIdentity.SourceClaim(
                                        editionId, "Opaque Edition", "English", true),
                                true),
                        "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language tag");
    }

    @Test
    void neverReusesAnActiveSourceBoundToAnotherEdition() {
        FakeJobs jobs = new FakeJobs();
        UUID firstEditionId = UUID.randomUUID();
        UUID selectedEditionId = UUID.randomUUID();
        var active = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", firstEditionId, "First Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(active);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        CatalogEditionLookup catalog = id -> {
            if (id.equals(firstEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        firstEditionId, GAME_ID, "Opaque Game", "First Edition", "en", java.util.Set.of()));
            }
            if (id.equals(selectedEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        selectedEditionId, GAME_ID, "Opaque Game", "Second Edition", "en", java.util.Set.of()));
            }
            return Optional.empty();
        };
        OfficialRulebookImportJobService service = service(
                jobs, imports, executor, mock(CatalogEditionLanguageConfirmation.class), catalog);
        var command = new OfficialRulebookImportJobService.Command(
                selectedEditionId,
                "Second Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                "Preserve this teaching preference",
                new OfficialRulebookImportIdentity.SourceClaim(
                        selectedEditionId, "Second Edition", "en", true),
                true);

        assertThatThrownBy(() -> service.enqueue(command, "alice"))
                .isInstanceOfSatisfying(OfficialRulebookImportIdentityException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            OfficialRulebookImportIdentityException.Code.ACTIVE_IMPORT_CONFLICT);
                    assertThat(failure.review().issues())
                            .contains(OfficialRulebookImportIdentity.Issue.PERSISTED_EDITION_CONFLICT);
                });
        verifyNoInteractions(executor, imports);
    }

    @Test
    void requiresReviewBeforeCreatingANewBindingForASourcePersistedUnderAnotherEdition() {
        FakeJobs jobs = new FakeJobs();
        UUID firstEditionId = UUID.randomUUID();
        UUID selectedEditionId = UUID.randomUUID();
        var completed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", firstEditionId, "First Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW.minusSeconds(30));
        jobs.insert(completed);
        jobs.complete(completed.id(), UUID.randomUUID(), false, NOW.minusSeconds(20));
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        CatalogEditionLookup catalog = id -> {
            if (id.equals(firstEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        firstEditionId, GAME_ID, "Opaque Game", "First Edition", "en", java.util.Set.of()));
            }
            if (id.equals(selectedEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        selectedEditionId, GAME_ID, "Opaque Game", "Second Edition", "en", java.util.Set.of()));
            }
            return Optional.empty();
        };
        OfficialRulebookImportJobService service = service(
                jobs, imports, executor, mock(CatalogEditionLanguageConfirmation.class), catalog);
        var unconfirmed = new OfficialRulebookImportJobService.Command(
                selectedEditionId,
                "Second Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                "Keep the second-edition teaching goal",
                new OfficialRulebookImportIdentity.SourceClaim(
                        selectedEditionId, "Second Edition", "en", true),
                false);

        assertThatThrownBy(() -> service.enqueue(unconfirmed, "alice"))
                .isInstanceOfSatisfying(OfficialRulebookImportIdentityException.class, failure ->
                        assertThat(failure.review().issues())
                                .containsExactly(OfficialRulebookImportIdentity.Issue.PERSISTED_EDITION_CONFLICT));
        verifyNoInteractions(executor, imports);

        var confirmed = new OfficialRulebookImportJobService.Command(
                selectedEditionId,
                "Second Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                "Keep the second-edition teaching goal",
                unconfirmed.sourceIdentity(),
                true);
        var launch = service.enqueue(confirmed, "alice");

        assertThat(launch.reused()).isFalse();
        assertThat(launch.job().editionId()).isEqualTo(selectedEditionId);
        assertThat(launch.job().teachingHandoff().learningGoal())
                .isEqualTo("Keep the second-edition teaching goal");
        verify(executor).execute(any());
        verifyNoInteractions(imports);
    }

    @Test
    void includesAManuallyPersistedDocumentInTheIdentityReview() {
        FakeJobs jobs = new FakeJobs();
        RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
        UUID firstEditionId = UUID.randomUUID();
        UUID selectedEditionId = UUID.randomUUID();
        when(documents.findLatestOwnedByOfficialSource("alice", SOURCE)).thenReturn(Optional.of(
                RuleDocument.create(
                        firstEditionId,
                        "Manually saved rules",
                        DocumentSourceType.BASE_RULEBOOK,
                        SOURCE,
                        null,
                        "alice",
                        NOW.minusSeconds(60))));
        CatalogEditionLookup catalog = id -> {
            if (id.equals(firstEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        firstEditionId, GAME_ID, "Opaque Game", "First Edition", "en", java.util.Set.of()));
            }
            if (id.equals(selectedEditionId)) {
                return Optional.of(new CatalogEditionLookup.EditionReference(
                        selectedEditionId, GAME_ID, "Opaque Game", "Second Edition", "en", java.util.Set.of()));
            }
            return Optional.empty();
        };
        OfficialRulebookImportJobService service = service(
                jobs,
                documents,
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                mock(CatalogEditionLanguageConfirmation.class),
                catalog);
        var command = new OfficialRulebookImportJobService.Command(
                selectedEditionId,
                "Second Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                false,
                null,
                new OfficialRulebookImportIdentity.SourceClaim(
                        selectedEditionId, "Second Edition", "en", true),
                false);

        assertThatThrownBy(() -> service.enqueue(command, "alice"))
                .isInstanceOfSatisfying(OfficialRulebookImportIdentityException.class, failure -> {
                    assertThat(failure.review().issues())
                            .containsExactly(OfficialRulebookImportIdentity.Issue.PERSISTED_EDITION_CONFLICT);
                    assertThat(failure.review().persisted()).singleElement().satisfies(persisted -> {
                        assertThat(persisted.source())
                                .isEqualTo(OfficialRulebookImportIdentity.PersistedSource.DOCUMENT);
                        assertThat(persisted.editionId()).isEqualTo(firstEditionId);
                    });
                });
    }

    @Test
    void reusesACompletedBoundImportAndUpgradesItToAutomaticTeaching() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        var completed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", editionId, "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(completed);
        jobs.complete(completed.id(), UUID.randomUUID(), false, NOW);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = service(jobs, imports, executor);

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.job().id()).isEqualTo(completed.id());
        assertThat(launch.job().teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        verifyNoInteractions(executor, imports);
    }

    @Test
    void restartsTeachingWhenAReusedVisualRulebookHasStaleDerivedEvidence() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID oldPreparationRunId = UUID.randomUUID();
        var completed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", editionId, "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(completed);
        jobs.complete(completed.id(), documentVersionId, false, NOW);
        jobs.claimReadyTeachingForDocument(documentVersionId, 1, NOW);
        jobs.completeTeachingLaunch(completed.id(), oldPreparationRunId, NOW);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(documentVersionId, oldPreparationRunId, "alice"))
                .thenReturn(ReuseAssessment.REFRESH_REQUIRED);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        OfficialRulebookImportJobService service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                imports,
                executor,
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.reused()).isTrue();
        assertThat(launch.job().teachingHandoff().state())
                .isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(launch.job().teachingHandoff().preparationRunId()).isNull();
        verify(freshness).assess(documentVersionId, oldPreparationRunId, "alice");
        verifyNoInteractions(executor, imports);
    }

    @Test
    void keepsAReusedTeachingHandoffWhenItsDerivedEvidenceIsCurrent() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID preparationRunId = UUID.randomUUID();
        Instant originalActivity = NOW.minus(Duration.ofHours(1));
        var completed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", editionId, "Example Rules", DocumentSourceType.BASE_RULEBOOK,
                SOURCE, true, null, originalActivity);
        jobs.insert(completed);
        jobs.complete(completed.id(), documentVersionId, false, originalActivity);
        jobs.claimReadyTeachingForDocument(documentVersionId, 1, originalActivity);
        jobs.completeTeachingLaunch(completed.id(), preparationRunId, originalActivity);
        jobs.insert(OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "More recent unrelated rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE + "?other", NOW.minusSeconds(1)));
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .thenReturn(ReuseAssessment.REUSABLE);
        OfficialRulebookImportJobService service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var launch = service.enqueue(automaticTeachingCommand(), "alice");

        assertThat(launch.job().teachingHandoff().state()).isEqualTo(TeachingHandoffState.LAUNCHED);
        assertThat(launch.job().teachingHandoff().preparationRunId()).isEqualTo(preparationRunId);
        assertThat(launch.job().updatedAt()).isEqualTo(NOW);
        assertThat(service.recentOwned("alice")).first().extracting(OfficialRulebookImportJob::id)
                .isEqualTo(completed.id());
        verify(freshness).assess(documentVersionId, preparationRunId, "alice");
    }

    @Test
    void refreshesStaleTeachingEvidenceWhenAPlayerResumesACompletedImport() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID preparationRunId = UUID.randomUUID();
        var completed = launchedJob(jobs, editionId, documentVersionId, preparationRunId, SOURCE);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(documentVersionId, preparationRunId, "alice"))
                .thenReturn(ReuseAssessment.REFRESH_REQUIRED);
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var refreshed = service.ensureTeachingCurrent(completed.id(), preparationRunId, "alice");

        assertThat(refreshed.teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(refreshed.teachingHandoff().preparationRunId()).isNull();
        verify(freshness).assess(documentVersionId, preparationRunId, "alice");
    }

    @Test
    void resumeFreshnessCheckCannotReplaceANewerPreparationRun() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID currentRunId = UUID.randomUUID();
        UUID staleBrowserRunId = UUID.randomUUID();
        var completed = launchedJob(jobs, editionId, documentVersionId, currentRunId, SOURCE);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var unchanged = service.ensureTeachingCurrent(completed.id(), staleBrowserRunId, "alice");

        assertThat(unchanged.teachingHandoff().state()).isEqualTo(TeachingHandoffState.LAUNCHED);
        assertThat(unchanged.teachingHandoff().preparationRunId()).isEqualTo(currentRunId);
        verifyNoInteractions(freshness);
    }

    @Test
    void reconcilesCompletedHandoffsByRestartingOnlyMissingTeachingResults() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID brokenVersionId = UUID.randomUUID();
        UUID brokenRunId = UUID.randomUUID();
        UUID usableVersionId = UUID.randomUUID();
        UUID usableRunId = UUID.randomUUID();
        var broken = launchedJob(jobs, editionId, brokenVersionId, brokenRunId, SOURCE);
        var usable = launchedJob(jobs, editionId, usableVersionId, usableRunId, SOURCE + "?usable");
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(brokenVersionId, brokenRunId, "alice"))
                .thenReturn(ReuseAssessment.REFRESH_REQUIRED);
        when(freshness.assess(usableVersionId, usableRunId, "alice"))
                .thenReturn(ReuseAssessment.REUSABLE);
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.restarted()).isEqualTo(1);
        assertThat(result.settled()).isEqualTo(1);
        assertThat(result.exhausted()).isZero();
        assertThat(jobs.findOwned(broken.id(), "alice").orElseThrow().teachingHandoff().state())
                .isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(jobs.findOwned(usable.id(), "alice").orElseThrow().teachingHandoff().state())
                .isEqualTo(TeachingHandoffState.LAUNCHED);
        assertThat(jobs.reconciled).containsExactly(usable.id());

        UUID recoveredRunId = UUID.randomUUID();
        jobs.claimReadyTeachingForDocument(brokenVersionId, 1, NOW.plusSeconds(1));
        jobs.completeTeachingLaunch(broken.id(), recoveredRunId, NOW.plusSeconds(2));
        when(freshness.assess(brokenVersionId, recoveredRunId, "alice"))
                .thenReturn(ReuseAssessment.REFRESH_REQUIRED);

        var secondPass = service.reconcileLaunched(4);

        assertThat(secondPass.restarted()).isZero();
        assertThat(secondPass.settled()).isZero();
        assertThat(secondPass.exhausted()).isOne();
        assertThat(jobs.findOwned(broken.id(), "alice").orElseThrow().teachingHandoff().state())
                .isEqualTo(TeachingHandoffState.FAILED);
        assertThat(jobs.findOwned(broken.id(), "alice").orElseThrow().teachingHandoff().errorCode())
                .isEqualTo("TEACHING_RECOVERY_EXHAUSTED");
        assertThat(jobs.findOwned(broken.id(), "alice").orElseThrow().teachingHandoff().preparationRunId())
                .isEqualTo(recoveredRunId);
        assertThat(jobs.reconciled).containsExactly(usable.id());
    }

    @Test
    void preservesTheFailedRunAndDoesNotBlindlyRetryADeterministicallyInvalidPlan() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        var failed = launchedJob(jobs, editionId, documentVersionId, failedRunId, SOURCE);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(documentVersionId, failedRunId, "alice"))
                .thenReturn(ReuseAssessment.TERMINAL_FAILURE);
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.restarted()).isZero();
        assertThat(result.exhausted()).isOne();
        var handoff = jobs.findOwned(failed.id(), "alice").orElseThrow().teachingHandoff();
        assertThat(handoff.state()).isEqualTo(TeachingHandoffState.FAILED);
        assertThat(handoff.preparationRunId()).isEqualTo(failedRunId);
        assertThat(handoff.errorCode()).isEqualTo("TEACHING_PREPARATION_INVALID_PLAN");
        assertThat(jobs.automaticallyRecovered).doesNotContain(failed.id());
    }

    @Test
    void preservesPlayerCancellationInsteadOfAutomaticallyRestartingTheGuide() {
        FakeJobs jobs = new FakeJobs();
        UUID editionId = automaticTeachingCommand().editionId();
        UUID documentVersionId = UUID.randomUUID();
        UUID cancelledRunId = UUID.randomUUID();
        var cancelled = launchedJob(jobs, editionId, documentVersionId, cancelledRunId, SOURCE);
        RulebookTeachingEvidenceFreshness freshness = mock(RulebookTeachingEvidenceFreshness.class);
        when(freshness.assess(documentVersionId, cancelledRunId, "alice"))
                .thenReturn(ReuseAssessment.CANCELLED);
        var service = new OfficialRulebookImportJobService(
                jobs,
                mock(RuleDocumentRepository.class),
                mock(OfficialRulebookImportService.class),
                mock(TaskExecutor.class),
                (edition, language) -> false,
                catalog(editionId, GAME_ID, "Opaque Edition", "en"),
                freshness,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.reconcileLaunched(4);

        assertThat(result.restarted()).isZero();
        assertThat(result.settled()).isOne();
        assertThat(jobs.findOwned(cancelled.id(), "alice").orElseThrow().teachingHandoff().state())
                .isEqualTo(TeachingHandoffState.NOT_REQUESTED);
        assertThat(jobs.automaticallyRecovered).doesNotContain(cancelled.id());
    }

    private OfficialRulebookImportJob launchedJob(
            FakeJobs jobs,
            UUID editionId,
            UUID documentVersionId,
            UUID preparationRunId,
            String source) {
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", editionId, "Example Rules", DocumentSourceType.BASE_RULEBOOK,
                source, true, null, NOW.minusSeconds(30));
        jobs.insert(job);
        jobs.complete(job.id(), documentVersionId, false, NOW.minusSeconds(20));
        jobs.claimReadyTeachingForDocument(documentVersionId, 1, NOW.minusSeconds(10));
        jobs.completeTeachingLaunch(job.id(), preparationRunId, NOW.minusSeconds(5));
        return jobs.findOwned(job.id(), "alice").orElseThrow();
    }

    @Test
    void exposesOversizedPdfCompressionAsARecoverableBackgroundStage() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        UUID versionId = UUID.randomUUID();
        doAnswer(invocation -> {
            OfficialRulebookSourceFetcher.ProgressListener progress = invocation.getArgument(6);
            progress.downloadStarted(60_000_000L);
            progress.downloaded(60_000_000L, 60_000_000L);
            progress.downloadCompleted();
            progress.compressing();
            progress.verifying();
            progress.saving();
            return uploadResult(versionId);
        }).when(imports).importRulebook(
                any(), anyString(), any(), anyString(), anyBoolean(), anyString(), any());
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var completed = service.requireOwned(launch.job().id(), "alice");

        assertThat(completed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.COMPLETED);
        assertThat(jobs.stages).containsSubsequence(
                OfficialRulebookImportJob.Stage.DOWNLOADING,
                OfficialRulebookImportJob.Stage.COMPRESSING,
                OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                OfficialRulebookImportJob.Stage.SAVING,
                OfficialRulebookImportJob.Stage.COMPLETED);
    }

    @Test
    void recordsAStableFailureCodeWhenTheBackgroundFetchFails() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        when(imports.importRulebook(any(), anyString(), any(), eq(SOURCE), anyBoolean(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("not a PDF"));
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var failed = service.requireOwned(launch.job().id(), "alice");

        assertThat(failed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(failed.errorCode()).isEqualTo("INVALID_PDF_SOURCE");
        assertThat(failed.completedAt()).isEqualTo(NOW);
    }

    @Test
    void preservesAnInteractiveBrowserRequirementAsARecoverableSourceState() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        when(imports.importRulebook(any(), anyString(), any(), eq(SOURCE), anyBoolean(), anyString(), any()))
                .thenThrow(new OfficialRulebookSourceAccessException(
                        OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED,
                        "BGG sign-in required"));
        OfficialRulebookImportJobService service = service(jobs, imports, Runnable::run);

        var launch = service.enqueue(command(), "alice");
        var failed = service.requireOwned(launch.job().id(), "alice");

        assertThat(failed.stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_BROWSER_REQUIRED");
    }

    @Test
    void turnsExecutorSaturationIntoAVisibleTerminalJob() {
        FakeJobs jobs = new FakeJobs();
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor rejected = task -> { throw new TaskRejectedException("full"); };
        OfficialRulebookImportJobService service = service(jobs, imports, rejected);

        var launch = service.enqueue(command(), "alice");

        assertThat(launch.job().stage()).isEqualTo(OfficialRulebookImportJob.Stage.FAILED);
        assertThat(launch.job().errorCode()).isEqualTo("IMPORT_QUEUE_FULL");
    }

    @Test
    void retriesOnlyATemporaryFailedSourceAsANewJobWithTheOriginalTeachingContext() {
        FakeJobs jobs = new FakeJobs();
        var failed = OfficialRulebookImportJob.queued(
                UUID.randomUUID(),
                "alice",
                automaticTeachingCommand().editionId(),
                "Example Rules",
                DocumentSourceType.OFFICIAL_FAQ,
                SOURCE,
                true,
                "重点讲清开局和第一轮。",
                NOW);
        jobs.insert(failed);
        jobs.fail(failed.id(), "SOURCE_UNAVAILABLE", NOW);
        TaskExecutor executor = mock(TaskExecutor.class);
        var service = service(jobs, mock(OfficialRulebookImportService.class), executor);

        var retry = service.retryImport(failed.id(), "alice");

        assertThat(retry.reused()).isFalse();
        assertThat(retry.job().id()).isNotEqualTo(failed.id());
        assertThat(retry.job()).satisfies(job -> {
            assertThat(job.stage()).isEqualTo(OfficialRulebookImportJob.Stage.QUEUED);
            assertThat(job.editionId()).isEqualTo(failed.editionId());
            assertThat(job.title()).isEqualTo("Example Rules");
            assertThat(job.sourceType()).isEqualTo(DocumentSourceType.OFFICIAL_FAQ);
            assertThat(job.sourceUrl()).isEqualTo(SOURCE);
            assertThat(job.teachingHandoff().learningGoal()).isEqualTo("重点讲清开局和第一轮。");
        });
        verify(executor).execute(any());
    }

    @Test
    void refusesToRetryAnInvalidSourceOrANonTerminalJob() {
        FakeJobs jobs = new FakeJobs();
        var invalid = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "Invalid Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, NOW);
        jobs.insert(invalid);
        jobs.fail(invalid.id(), "INVALID_PDF_SOURCE", NOW);
        var running = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "Running Rules",
                DocumentSourceType.BASE_RULEBOOK, "https://publisher.example/running.pdf", NOW);
        jobs.insert(running);
        var service = service(
                jobs, mock(OfficialRulebookImportService.class), mock(TaskExecutor.class));

        assertThatThrownBy(() -> service.retryImport(invalid.id(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not retryable");
        assertThatThrownBy(() -> service.retryImport(running.id(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not retryable");
    }

    @Test
    void retriesTheExistingDurableTeachingHandoffWithoutDownloadingTheRulebookAgain() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), failedRunId, NOW);
        OfficialRulebookImportService imports = mock(OfficialRulebookImportService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        var service = service(jobs, imports, executor);

        var retried = service.retryTeaching(job.id(), failedRunId, "alice");

        assertThat(retried.documentVersionId()).isEqualTo(versionId);
        assertThat(retried.teachingHandoff().state()).isEqualTo(TeachingHandoffState.WAITING_FOR_DOCUMENT);
        assertThat(retried.teachingHandoff().preparationRunId()).isNull();
        verifyNoInteractions(executor, imports);
    }

    @Test
    void aStaleDuplicateRetryCannotReplaceTheNewerPreparationRun() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        UUID oldRunId = UUID.randomUUID();
        UUID newerRunId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), oldRunId, NOW);
        var service = service(jobs, mock(OfficialRulebookImportService.class), mock(TaskExecutor.class));
        service.retryTeaching(job.id(), oldRunId, "alice");
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.completeTeachingLaunch(job.id(), newerRunId, NOW);

        var unchanged = service.retryTeaching(job.id(), oldRunId, "alice");

        assertThat(unchanged.teachingHandoff().state()).isEqualTo(TeachingHandoffState.LAUNCHED);
        assertThat(unchanged.teachingHandoff().preparationRunId()).isEqualTo(newerRunId);
    }

    @Test
    void doesNotRetryTeachingWhenThePersistedRulebookProcessingFailed() {
        FakeJobs jobs = new FakeJobs();
        UUID versionId = UUID.randomUUID();
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", automaticTeachingCommand().editionId(), "Example Rules",
                DocumentSourceType.BASE_RULEBOOK, SOURCE, true, null, NOW);
        jobs.insert(job);
        jobs.complete(job.id(), versionId, false, NOW);
        jobs.claimReadyTeachingForDocument(versionId, 1, NOW);
        jobs.failTeachingLaunch(job.id(), "DOCUMENT_PROCESSING_FAILED", NOW);
        var service = service(jobs, mock(OfficialRulebookImportService.class), mock(TaskExecutor.class));

        assertThatThrownBy(() -> service.retryTeaching(job.id(), null, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rulebook processing failed");
    }

    private OfficialRulebookImportJobService service(
            FakeJobs jobs, OfficialRulebookImportService imports, TaskExecutor executor) {
        return service(
                jobs,
                mock(RuleDocumentRepository.class),
                imports,
                executor,
                (editionId, language) -> false,
                catalog(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        GAME_ID,
                        "Opaque Edition",
                        "en"));
    }

    private OfficialRulebookImportJobService service(
            FakeJobs jobs,
            OfficialRulebookImportService imports,
            TaskExecutor executor,
            CatalogEditionLanguageConfirmation languages) {
        return service(
                jobs,
                mock(RuleDocumentRepository.class),
                imports,
                executor,
                languages,
                catalog(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        GAME_ID,
                        "Opaque Edition",
                        "en"));
    }

    private OfficialRulebookImportJobService service(
            FakeJobs jobs,
            RuleDocumentRepository documents,
            OfficialRulebookImportService imports,
            TaskExecutor executor,
            CatalogEditionLanguageConfirmation languages,
            CatalogEditionLookup catalog) {
        return new OfficialRulebookImportJobService(
                jobs, documents, imports, executor, languages, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OfficialRulebookImportJobService service(
            FakeJobs jobs,
            OfficialRulebookImportService imports,
            TaskExecutor executor,
            CatalogEditionLanguageConfirmation languages,
            CatalogEditionLookup catalog) {
        return service(jobs, mock(RuleDocumentRepository.class), imports, executor, languages, catalog);
    }

    private CatalogEditionLookup catalog(
            UUID editionId, UUID gameId, String editionName, String language) {
        return requested -> requested.equals(editionId)
                ? Optional.of(new CatalogEditionLookup.EditionReference(
                        editionId, gameId, "Opaque Game", editionName, language, java.util.Set.of()))
                : Optional.empty();
    }

    private OfficialRulebookImportJobService.Command command() {
        return new OfficialRulebookImportJobService.Command(
                null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, true);
    }

    private OfficialRulebookImportJobService.Command automaticTeachingCommand() {
        UUID editionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new OfficialRulebookImportJobService.Command(
                editionId,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                SOURCE,
                true,
                true,
                "重点讲清开局和第一轮。",
                new OfficialRulebookImportIdentity.SourceClaim(
                        editionId, "Opaque Edition", "en", true),
                true);
    }

    private UploadRuleDocumentService.UploadResult uploadResult(UUID versionId) {
        UUID documentId = UUID.randomUUID();
        RuleDocument document = RuleDocument.create(
                null, "Example Rules", DocumentSourceType.BASE_RULEBOOK, SOURCE, null, "alice", NOW);
        DocumentVersion version = new DocumentVersion(
                versionId, documentId, 1, "official-rulebook.pdf", "documents/example.pdf",
                "a".repeat(64), 1_024, "application/pdf", ProcessingStatus.UPLOADED, NOW);
        return new UploadRuleDocumentService.UploadResult(document, version, false);
    }

    private static final class FakeJobs implements OfficialRulebookImportJobRepository {
        private final Map<UUID, OfficialRulebookImportJob> values = new LinkedHashMap<>();
        private final List<OfficialRulebookImportJob.Stage> stages = new ArrayList<>();
        private final List<UUID> reconciled = new ArrayList<>();
        private final java.util.Set<UUID> automaticallyRecovered = new java.util.HashSet<>();

        private long downloadWriteCount() {
            return stages.stream().filter(stage -> stage == OfficialRulebookImportJob.Stage.DOWNLOADING).count();
        }

        @Override public void insert(OfficialRulebookImportJob job) { values.put(job.id(), job); }

        @Override
        public Optional<OfficialRulebookImportJob> findOwned(UUID jobId, String ownerUsername) {
            return Optional.ofNullable(values.get(jobId)).filter(job -> job.ownerUsername().equals(ownerUsername));
        }

        @Override
        public Optional<OfficialRulebookImportJob> findActiveOwnedBySource(String ownerUsername, String sourceUrl) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl) && !job.stage().terminal())
                    .findFirst();
        }

        @Override
        public Optional<OfficialRulebookImportJob> findLatestOwnedBySource(String ownerUsername, String sourceUrl) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<OfficialRulebookImportJob> findCompletedOwnedBySourceAndEdition(
                String ownerUsername, String sourceUrl, UUID editionId) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .filter(job -> job.sourceUrl().equals(sourceUrl))
                    .filter(job -> java.util.Objects.equals(job.editionId(), editionId))
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED
                            && job.documentVersionId() != null)
                    .findFirst();
        }

        @Override
        public List<OfficialRulebookImportJob> findRecentOwned(String ownerUsername, int limit) {
            return values.values().stream()
                    .filter(job -> job.ownerUsername().equals(ownerUsername))
                    .sorted(java.util.Comparator.comparing(OfficialRulebookImportJob::updatedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<OfficialRulebookImportJob> findUnreconciledLaunchedTeaching(int limit) {
            return values.values().stream()
                    .filter(job -> job.teachingHandoff().state() == TeachingHandoffState.LAUNCHED)
                    .filter(job -> !reconciled.contains(job.id()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void recordReuse(UUID jobId, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    job.teachingHandoff(), now, job.completedAt()));
        }

        @Override
        public void requestTeaching(UUID jobId, String learningGoal, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    TeachingHandoff.requested(learningGoal, now), now, job.completedAt()));
        }

        @Override
        public boolean retryTeaching(UUID jobId, UUID expectedPreparationRunId, Instant now) {
            var job = values.get(jobId);
            boolean eligible = job.teachingHandoff().state() == TeachingHandoffState.FAILED
                    || job.teachingHandoff().state() == TeachingHandoffState.LAUNCHED
                            && java.util.Objects.equals(
                                    job.teachingHandoff().preparationRunId(), expectedPreparationRunId);
            if (!eligible) return false;
            automaticallyRecovered.remove(jobId);
            reconciled.remove(jobId);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    TeachingHandoff.requested(job.teachingHandoff().learningGoal(), now),
                    now, job.completedAt()));
            return true;
        }

        @Override
        public boolean retryTeachingAutomatically(UUID jobId, UUID expectedPreparationRunId, Instant now) {
            var job = values.get(jobId);
            if (job == null
                    || automaticallyRecovered.contains(jobId)
                    || job.teachingHandoff().state() != TeachingHandoffState.LAUNCHED
                    || !java.util.Objects.equals(job.teachingHandoff().preparationRunId(), expectedPreparationRunId)) {
                return false;
            }
            automaticallyRecovered.add(jobId);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.WAITING_FOR_DOCUMENT,
                            job.teachingHandoff().learningGoal(),
                            null,
                            null,
                            1,
                            now),
                    now, job.completedAt()));
            return true;
        }

        @Override
        public boolean failTeachingRecoveryExhausted(UUID jobId, UUID expectedPreparationRunId, Instant now) {
            var job = values.get(jobId);
            if (job == null
                    || job.teachingHandoff().state() != TeachingHandoffState.LAUNCHED
                    || job.teachingHandoff().automaticRecoveryCount() != 1
                    || !java.util.Objects.equals(job.teachingHandoff().preparationRunId(), expectedPreparationRunId)) {
                return false;
            }
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.FAILED,
                            job.teachingHandoff().learningGoal(),
                            expectedPreparationRunId,
                            "TEACHING_RECOVERY_EXHAUSTED",
                            1,
                            now),
                    now, job.completedAt()));
            return true;
        }

        @Override
        public boolean failTeachingTerminal(
                UUID jobId, UUID expectedPreparationRunId, String errorCode, Instant now) {
            var job = values.get(jobId);
            if (job == null
                    || job.teachingHandoff().state() != TeachingHandoffState.LAUNCHED
                    || !java.util.Objects.equals(job.teachingHandoff().preparationRunId(), expectedPreparationRunId)) {
                return false;
            }
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(),
                    new TeachingHandoff(
                            TeachingHandoffState.FAILED,
                            job.teachingHandoff().learningGoal(),
                            expectedPreparationRunId,
                            errorCode,
                            job.teachingHandoff().automaticRecoveryCount(),
                            now),
                    now, job.completedAt()));
            return true;
        }

        @Override
        public boolean markTeachingReconciled(UUID jobId, UUID expectedPreparationRunId, Instant now) {
            var job = values.get(jobId);
            if (job == null
                    || job.teachingHandoff().state() != TeachingHandoffState.LAUNCHED
                    || !java.util.Objects.equals(job.teachingHandoff().preparationRunId(), expectedPreparationRunId)
                    || reconciled.contains(jobId)) {
                return false;
            }
            reconciled.add(jobId);
            return true;
        }

        @Override
        public boolean dismissTeaching(
                UUID jobId,
                String ownerUsername,
                OfficialRulebookImportJob.TeachingHandoffState expectedState,
                UUID expectedPreparationRunId,
                Instant now) {
            var job = values.get(jobId);
            if (job == null
                    || !job.ownerUsername().equals(ownerUsername)
                    || job.teachingHandoff().state() != expectedState
                    || !java.util.Objects.equals(
                            job.teachingHandoff().preparationRunId(), expectedPreparationRunId)) {
                return false;
            }
            values.put(jobId, copy(
                    job,
                    job.stage(),
                    job.downloadedBytes(),
                    job.totalBytes(),
                    job.documentVersionId(),
                    job.duplicate(),
                    job.errorCode(),
                    TeachingHandoff.notRequested(),
                    now,
                    job.completedAt()));
            automaticallyRecovered.remove(jobId);
            reconciled.remove(jobId);
            return true;
        }

        @Override
        public int dismissTeachingForDocumentVersion(
                UUID documentVersionId, String ownerUsername, Instant now) {
            return 0;
        }

        @Override
        public List<OfficialRulebookImportJob> claimReadyTeaching(int limit, Instant now) {
            return claimReadyTeaching(null, limit, now);
        }

        @Override
        public List<OfficialRulebookImportJob> claimReadyTeachingForDocument(
                UUID documentVersionId, int limit, Instant now) {
            return claimReadyTeaching(documentVersionId, limit, now);
        }

        private List<OfficialRulebookImportJob> claimReadyTeaching(
                UUID documentVersionId, int limit, Instant now) {
            List<OfficialRulebookImportJob> claimed = values.values().stream()
                    .filter(job -> job.stage() == OfficialRulebookImportJob.Stage.COMPLETED)
                    .filter(job -> job.teachingHandoff().state() == TeachingHandoffState.WAITING_FOR_DOCUMENT)
                    .filter(job -> documentVersionId == null || documentVersionId.equals(job.documentVersionId()))
                    .limit(limit)
                    .toList();
            for (var job : claimed) {
                var launching = new TeachingHandoff(
                        TeachingHandoffState.LAUNCHING,
                        job.teachingHandoff().learningGoal(),
                        null,
                        null,
                        job.teachingHandoff().automaticRecoveryCount(),
                        now);
                values.put(job.id(), copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                        job.documentVersionId(), job.duplicate(), job.errorCode(), launching, now, job.completedAt()));
            }
            return claimed.stream().map(job -> values.get(job.id())).toList();
        }

        @Override public int failTeachingForUnusableDocuments(Instant now) { return 0; }

        @Override
        public void completeTeachingLaunch(UUID jobId, UUID preparationRunId, Instant now) {
            var job = values.get(jobId);
            var launched = new TeachingHandoff(
                    TeachingHandoffState.LAUNCHED,
                    job.teachingHandoff().learningGoal(),
                    preparationRunId,
                    null,
                    job.teachingHandoff().automaticRecoveryCount(),
                    now);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(), launched, now, job.completedAt()));
        }

        @Override
        public void failTeachingLaunch(UUID jobId, String errorCode, Instant now) {
            var job = values.get(jobId);
            var failed = new TeachingHandoff(
                    TeachingHandoffState.FAILED,
                    job.teachingHandoff().learningGoal(),
                    null,
                    errorCode,
                    job.teachingHandoff().automaticRecoveryCount(),
                    now);
            values.put(jobId, copy(job, job.stage(), job.downloadedBytes(), job.totalBytes(),
                    job.documentVersionId(), job.duplicate(), job.errorCode(), failed, now, job.completedAt()));
        }

        @Override public int failInterruptedTeachingLaunches(Instant now) { return 0; }

        @Override
        public void markDownloadCompleted(UUID jobId, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, new OfficialRulebookImportJob(
                    job.id(), job.ownerUsername(), job.editionId(), job.title(), job.sourceType(), job.sourceUrl(),
                    job.stage(), job.downloadedBytes(), job.totalBytes(), job.documentVersionId(), job.duplicate(),
                    job.errorCode(), now, job.teachingHandoff(), job.createdAt(), now, job.completedAt()));
        }

        @Override
        public void updateProgress(
                UUID jobId, OfficialRulebookImportJob.Stage stage, long downloadedBytes, Long totalBytes, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, stage, downloadedBytes, totalBytes, null, false, null,
                    job.teachingHandoff(), now, null));
            stages.add(stage);
        }

        @Override
        public void complete(UUID jobId, UUID documentVersionId, boolean duplicate, Instant now) {
            var job = values.get(jobId);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.COMPLETED,
                    job.downloadedBytes(), job.totalBytes(), documentVersionId, duplicate, null,
                    job.teachingHandoff(), now, now));
            stages.add(OfficialRulebookImportJob.Stage.COMPLETED);
        }

        @Override
        public void fail(UUID jobId, String errorCode, Instant now) {
            var job = values.get(jobId);
            TeachingHandoff handoff = job.teachingHandoff().state() == TeachingHandoffState.NOT_REQUESTED
                    ? job.teachingHandoff()
                    : new TeachingHandoff(
                            TeachingHandoffState.FAILED,
                            job.teachingHandoff().learningGoal(),
                            null,
                            "IMPORT_FAILED",
                            job.teachingHandoff().automaticRecoveryCount(),
                            now);
            values.put(jobId, copy(job, OfficialRulebookImportJob.Stage.FAILED,
                    job.downloadedBytes(), job.totalBytes(), null, false, errorCode, handoff, now, now));
            stages.add(OfficialRulebookImportJob.Stage.FAILED);
        }

        @Override public int failInterrupted(Instant now) { return 0; }

        private OfficialRulebookImportJob copy(
                OfficialRulebookImportJob job,
                OfficialRulebookImportJob.Stage stage,
                long downloadedBytes,
                Long totalBytes,
                UUID documentVersionId,
                boolean duplicate,
                String errorCode,
                TeachingHandoff teachingHandoff,
                Instant updatedAt,
                Instant completedAt) {
            return new OfficialRulebookImportJob(
                    job.id(), job.ownerUsername(), job.editionId(), job.title(), job.sourceType(), job.sourceUrl(),
                    stage, downloadedBytes, totalBytes, documentVersionId, duplicate, errorCode,
                    job.downloadCompletedAt(), teachingHandoff, job.createdAt(), updatedAt, completedAt);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
