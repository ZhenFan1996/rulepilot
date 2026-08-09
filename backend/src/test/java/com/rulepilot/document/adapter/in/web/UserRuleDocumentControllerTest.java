package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.application.PhotographedRulebookUploadService;
import com.rulepilot.document.application.RuleDocumentMetadataSuggestionService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService;
import com.rulepilot.document.application.RuleDocumentMetadataConfirmationService.Confirmation;
import com.rulepilot.document.application.RuleDocumentRemovalService;
import com.rulepilot.document.application.OfficialRulebookImportJobService;
import com.rulepilot.document.application.UploadRuleDocumentService;
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
    void passesOfficialImportConsentAndOwnerToTheRightsAwarePipeline() {
        OfficialRulebookImportJobService imports = mock(OfficialRulebookImportJobService.class);
        UserRuleDocumentController controller = new UserRuleDocumentController(
                mock(UploadRuleDocumentService.class),
                mock(PhotographedRulebookUploadService.class),
                mock(RuleDocumentRemovalService.class),
                mock(RuleDocumentMetadataSuggestionService.class),
                mock(RuleDocumentMetadataConfirmationService.class),
                imports);
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        var job = OfficialRulebookImportJob.queued(
                jobId,
                "alice",
                null,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf",
                now);
        var command = new OfficialRulebookImportJobService.Command(
                null, "Example Rules", DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf", true);
        when(imports.enqueue(command, "alice"))
                .thenReturn(new OfficialRulebookImportJobService.Launch(job, false));

        var response = controller.importOfficialRulebook(
                new UserRuleDocumentController.OfficialRulebookImportRequest(
                        null,
                        "Example Rules",
                        DocumentSourceType.BASE_RULEBOOK,
                        "https://publisher.example/rules.pdf",
                        true),
                () -> "alice");

        assertThat(response.id()).isEqualTo(jobId);
        assertThat(response.sourceDomain()).isEqualTo("publisher.example");
        assertThat(response.stage()).isEqualTo(OfficialRulebookImportJob.Stage.QUEUED);
        verify(imports).enqueue(command, "alice");
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
                mock(OfficialRulebookImportJobService.class));
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
                mock(OfficialRulebookImportJobService.class));
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
