package com.rulepilot.document.application;

import com.rulepilot.catalog.BoardGameMetadataLinking;
import com.rulepilot.catalog.BoardGameMetadataLinking.Link;
import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.domain.RuleDocument;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RuleDocumentMetadataConfirmationService {

    private final RuleDocumentMetadataSuggestionService suggestions;
    private final BoardGameMetadataLinking catalogLinking;
    private final UploadRuleDocumentService documents;

    public RuleDocumentMetadataConfirmationService(
            RuleDocumentMetadataSuggestionService suggestions,
            BoardGameMetadataLinking catalogLinking,
            UploadRuleDocumentService documents) {
        this.suggestions = suggestions;
        this.catalogLinking = catalogLinking;
        this.documents = documents;
    }

    public Confirmation confirm(UUID documentId, int bggId, String ownerUsername) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        Candidate selected = suggestions.suggest(documentId, ownerUsername).stream()
                .filter(candidate -> candidate.bggId() == bggId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selected BGG game is not a current document candidate"));
        Link link = catalogLinking.confirm(bggId);
        RuleDocument assigned = documents.assign(documentId, link.editionId(), ownerUsername);
        return new Confirmation(assigned, selected, link);
    }

    public record Confirmation(RuleDocument document, Candidate candidate, Link link) {}
}
