package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.application.OfficialRulebookSourceFetcher.FetchedRulebook;
import com.rulepilot.document.domain.DocumentSourceType;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OfficialRulebookImportServiceTest {

    private final OfficialRulebookSourceFetcher sources = mock(OfficialRulebookSourceFetcher.class);
    private final UploadRuleDocumentService documents = mock(UploadRuleDocumentService.class);
    private final OfficialRulebookImportService service = new OfficialRulebookImportService(sources, documents);

    @Test
    void requiresRightsConfirmationBeforeNetworkAccess() {
        assertThatThrownBy(() -> service.importRulebook(
                        null,
                        "Example Rules",
                        DocumentSourceType.BASE_RULEBOOK,
                        "https://publisher.example/rules.pdf",
                        false,
                        "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rights confirmation");
        verify(sources, never()).fetch(any());
    }

    @Test
    void sendsValidatedPublisherPdfThroughTheExistingUploadPipeline() throws Exception {
        URI source = URI.create("https://publisher.example/rules.pdf");
        byte[] pdf = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);
        when(sources.fetch(eq(source), any(OfficialRulebookSourceFetcher.ProgressListener.class)))
                .thenReturn(new FetchedRulebook(source, pdf));
        UUID editionId = UUID.randomUUID();

        service.importRulebook(
                editionId,
                "Example Rules",
                DocumentSourceType.BASE_RULEBOOK,
                source.toString(),
                true,
                "alice");

        ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
        verify(documents).upload(
                org.mockito.ArgumentMatchers.eq(editionId),
                org.mockito.ArgumentMatchers.eq("Example Rules"),
                org.mockito.ArgumentMatchers.eq(DocumentSourceType.BASE_RULEBOOK),
                org.mockito.ArgumentMatchers.eq(source.toString()),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("official-rulebook.pdf"),
                org.mockito.ArgumentMatchers.eq("application/pdf"),
                org.mockito.ArgumentMatchers.eq((long) pdf.length),
                content.capture(),
                org.mockito.ArgumentMatchers.eq("alice"));
        assertThat(content.getValue().readAllBytes()).isEqualTo(pdf);
    }
}
