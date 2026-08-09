package com.rulepilot.document.application;

import com.rulepilot.document.domain.DocumentSourceType;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class OfficialRulebookImportService {

    private final OfficialRulebookSourceFetcher sources;
    private final UploadRuleDocumentService documents;

    public OfficialRulebookImportService(
            OfficialRulebookSourceFetcher sources, UploadRuleDocumentService documents) {
        this.sources = sources;
        this.documents = documents;
    }

    public UploadRuleDocumentService.UploadResult importRulebook(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            boolean rightsConfirmed,
            String ownerUsername) {
        return importRulebook(
                editionId,
                title,
                sourceType,
                officialSourceUrl,
                rightsConfirmed,
                ownerUsername,
                OfficialRulebookSourceFetcher.ProgressListener.none());
    }

    public UploadRuleDocumentService.UploadResult importRulebook(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            boolean rightsConfirmed,
            String ownerUsername,
            OfficialRulebookSourceFetcher.ProgressListener progress) {
        if (!rightsConfirmed) {
            throw new IllegalArgumentException("official source rights confirmation is required");
        }
        URI source;
        try {
            source = URI.create(officialSourceUrl == null ? "" : officialSourceUrl.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("official rulebook source URL is invalid", exception);
        }
        var fetched = sources.fetch(source, progress);
        byte[] content = fetched.content();
        progress.saving();
        return documents.upload(
                editionId,
                title,
                sourceType,
                source.toASCIIString(),
                null,
                "official-rulebook.pdf",
                RuleDocumentStorageService.PDF_CONTENT_TYPE,
                content.length,
                new ByteArrayInputStream(content),
                ownerUsername);
    }
}
