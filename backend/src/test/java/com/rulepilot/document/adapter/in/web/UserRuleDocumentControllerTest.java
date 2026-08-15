package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.application.PhotographedRulebookUploadService;
import com.rulepilot.document.application.RuleDocumentMetadataSuggestionService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService.Confirmation;
import com.rulepilot.document.application.RuleDocumentRemovalService;
import com.rulepilot.document.application.OfficialRulebookImportJobService;
import com.rulepilot.document.application.OfficialRulebookImportIdentity;
import com.rulepilot.document.application.UploadRuleDocumentService;
import com.rulepilot.document.application.UploadedRulebookTeachingHandoffService;
import com.rulepilot.catalog.BoardGameMetadataLinking.Link;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.RuleDocument;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserRuleDocumentControllerTest {

    @Test
    void exposesTheCatalogGameNameForAPersistedPlayerUploadHandoff() {
        UploadedRulebookTeachingHandoffService handoffs = mock(UploadedRulebookTeachingHandoffService.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                mock(OfficialRulebookImportJobService.class),
                handoffs,
                catalog);
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        when(handoffs.recentOwned("alice")).thenReturn(List.of(
                new UploadedRulebookTeachingHandoffService.HandoffView(
                        handoffId,
                        versionId,
                        editionId,
                        "rules_v4_final.pdf",
                        com.rulepilot.document.application.UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT,
                        null,
                        null,
                        now,
                        now)));
        when(catalog.findEdition(editionId)).thenReturn(java.util.Optional.of(
                new CatalogEditionLookup.EditionReference(
                        editionId, gameId, "星际探险", "中文版", "zh-CN", java.util.Set.of())));

        var response = controller.uploadedRulebookTeachingHandoffs(() -> "alice");

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("星际探险");
            assertThat(item.rulebookTitle()).isEqualTo("rules_v4_final.pdf");
            assertThat(item.documentVersionId()).isEqualTo(versionId);
            assertThat(item.state()).isEqualTo("WAITING_FOR_DOCUMENT");
        });
    }

    @Test
    void passesOfficialImportConsentAndOwnerToTheRightsAwarePipeline() {
        OfficialRulebookImportJobService imports = mock(OfficialRulebookImportJobService.class);
        CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                imports,
                mock(UploadedRulebookTeachingHandoffService.class),
                catalog);
        UUID jobId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        var job = OfficialRulebookImportJob.queued(
                jobId,
                "alice",
                editionId,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf",
                true,
                "重点讲清开局和第一轮。",
                now);
        var command = new OfficialRulebookImportJobService.Command(
                editionId, "Example Rules", DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf", true, true, "重点讲清开局和第一轮。",
                new OfficialRulebookImportIdentity.SourceClaim(
                        editionId, "English Edition", "en", true),
                true);
        when(imports.enqueue(command, "alice"))
                .thenReturn(new OfficialRulebookImportJobService.Launch(job, false));
        when(catalog.findEdition(editionId)).thenReturn(java.util.Optional.of(
                new CatalogEditionLookup.EditionReference(
                        editionId, gameId, "Example Game", "中文版", "zh-CN", java.util.Set.of())));

        var response = controller.importOfficialRulebook(
                new UserRuleDocumentController.OfficialRulebookImportRequest(
                        editionId,
                        "Example Rules",
                        DocumentSourceType.BASE_RULEBOOK,
                        "https://publisher.example/rules.pdf",
                        true,
                        true,
                        "重点讲清开局和第一轮。",
                        editionId,
                        "English Edition",
                        "en",
                        true,
                        true),
                () -> "alice");

        assertThat(response.id()).isEqualTo(jobId);
        assertThat(response.title()).isEqualTo("Example Game");
        assertThat(response.rulebookTitle()).isEqualTo("Example Rules");
        assertThat(response.editionId()).isEqualTo(editionId);
        assertThat(response.sourceDomain()).isEqualTo("publisher.example");
        assertThat(response.stage()).isEqualTo(OfficialRulebookImportJob.Stage.QUEUED);
        assertThat(response.teachingHandoffState())
                .isEqualTo(OfficialRulebookImportJob.TeachingHandoffState.WAITING_FOR_DOCUMENT);
        verify(imports).enqueue(command, "alice");
    }

    @Test
    void exposesPersistedDownloadImportAndTeachingHandoffMilestones() {
        OfficialRulebookImportJobService imports = mock(OfficialRulebookImportJobService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                imports,
                mock(UploadedRulebookTeachingHandoffService.class),
                mock(CatalogEditionLookup.class));
        UUID jobId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID preparationRunId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-13T08:00:00Z");
        Instant downloadCompletedAt = createdAt.plusSeconds(2);
        Instant importCompletedAt = createdAt.plusSeconds(4);
        Instant handoffLaunchedAt = createdAt.plusSeconds(7);
        var job = new OfficialRulebookImportJob(
                jobId,
                "alice",
                null,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf",
                OfficialRulebookImportJob.Stage.COMPLETED,
                1_024,
                1_024L,
                versionId,
                false,
                null,
                downloadCompletedAt,
                new OfficialRulebookImportJob.TeachingHandoff(
                        OfficialRulebookImportJob.TeachingHandoffState.LAUNCHED,
                        null,
                        preparationRunId,
                        null,
                        handoffLaunchedAt),
                createdAt,
                handoffLaunchedAt,
                importCompletedAt);
        when(imports.requireOwned(jobId, "alice")).thenReturn(job);

        var response = controller.officialRulebookImport(jobId, () -> "alice");

        assertThat(response.downloadCompletedAt()).isEqualTo(downloadCompletedAt);
        assertThat(response.importCompletedAt()).isEqualTo(importCompletedAt);
        assertThat(response.teachingHandoffUpdatedAt()).isEqualTo(handoffLaunchedAt);
        assertThat(response.teachingPreparationRunId()).isEqualTo(preparationRunId);
    }

    @Test
    void retriesOfficialTeachingThroughTheOwnedDurableImportHandoff() {
        OfficialRulebookImportJobService imports = mock(OfficialRulebookImportJobService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                imports,
                mock(UploadedRulebookTeachingHandoffService.class),
                mock(CatalogEditionLookup.class));
        UUID jobId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        var job = OfficialRulebookImportJob.queued(
                jobId, "alice", null, "Example Rules", DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf", true, null, now);
        when(imports.retryTeaching(jobId, failedRunId, "alice")).thenReturn(job);

        var response = controller.retryOfficialRulebookTeaching(
                jobId,
                new UserRuleDocumentController.TeachingHandoffRetryRequest(failedRunId),
                () -> "alice");

        assertThat(response.id()).isEqualTo(jobId);
        verify(imports).retryTeaching(jobId, failedRunId, "alice");
    }

    @Test
    void retriesUploadedTeachingThroughTheOwnedDurableUploadHandoff() {
        UploadedRulebookTeachingHandoffService handoffs = mock(UploadedRulebookTeachingHandoffService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                mock(OfficialRulebookImportJobService.class),
                handoffs,
                mock(CatalogEditionLookup.class));
        UUID handoffId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        var view = new UploadedRulebookTeachingHandoffService.HandoffView(
                handoffId,
                versionId,
                null,
                "rules.pdf",
                com.rulepilot.document.application.UploadedRulebookTeachingHandoffStore.State.WAITING_FOR_DOCUMENT,
                null,
                null,
                now,
                now);
        when(handoffs.retry(handoffId, failedRunId, "alice")).thenReturn(view);

        var response = controller.retryUploadedRulebookTeaching(
                handoffId,
                new UserRuleDocumentController.TeachingHandoffRetryRequest(failedRunId),
                () -> "alice");

        assertThat(response.id()).isEqualTo(handoffId);
        verify(handoffs).retry(handoffId, failedRunId, "alice");
    }

    @Test
    void exposesOwnerScopedAttributedSuggestions() {
        RuleDocumentMetadataSuggestionService suggestions = mock(RuleDocumentMetadataSuggestionService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                suggestions,
                mock(RuleDocumentMetadataConfirmationService.class),
                mock(OfficialRulebookImportJobService.class),
                mock(UploadedRulebookTeachingHandoffService.class),
                mock(CatalogEditionLookup.class));
        UUID documentId = UUID.randomUUID();
        Principal principal = () -> "alice";
        when(suggestions.suggest(documentId, "alice"))
                .thenReturn(List.of(new Candidate(
                        266192,
                        "Wingspan",
                        2019,
                        "https://example.test/wingspan.jpg",
                        1,
                        5,
                        70,
                        10,
                        true)));

        var response = controller.bggSuggestions(documentId, principal);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().normalizedTitleMatch()).isTrue();
        assertThat(response.getFirst().bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/266192");
        verify(suggestions).suggest(documentId, "alice");
    }

    @Test
    void returnsThePersistedCatalogAndDocumentIdentityAfterConfirmation() {
        RuleDocumentMetadataConfirmationService confirmations = mock(RuleDocumentMetadataConfirmationService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                confirmations,
                mock(OfficialRulebookImportJobService.class),
                mock(UploadedRulebookTeachingHandoffService.class),
                mock(CatalogEditionLookup.class));
        UUID documentId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        Candidate candidate = new Candidate(266192, "Wingspan", 2019, "", 1, 5, 70, 10, true);
        RuleDocument document = new RuleDocument(
                documentId,
                editionId,
                "Wingspan",
                DocumentSourceType.BASE_RULEBOOK,
                "alice",
                Instant.parse("2026-08-06T00:00:00Z"));
        when(confirmations.confirm(documentId, 266192, "alice")).thenReturn(new Confirmation(
                document,
                candidate,
                new Link(gameId, editionId, 266192, "Wingspan", "https://example.test/cover.jpg", true)));

        var response = controller.confirmBggLink(
                documentId, new UserRuleDocumentController.ConfirmBggLinkRequest(266192), () -> "alice");

        assertThat(response.document().gameEditionId()).isEqualTo(editionId);
        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.alreadyImported()).isTrue();
        assertThat(response.bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/266192");
    }
}
