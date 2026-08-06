package com.rulepilot.document.application;

import com.rulepilot.catalog.BoardGameMetadataMatching;
import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.domain.ProcessingStatus;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RuleDocumentMetadataSuggestionService {

    private final RuleDocumentRepository documents;
    private final BoardGameMetadataMatching catalogMatching;

    public RuleDocumentMetadataSuggestionService(
            RuleDocumentRepository documents, BoardGameMetadataMatching catalogMatching) {
        this.documents = documents;
        this.catalogMatching = catalogMatching;
    }

    @Transactional(readOnly = true)
    public List<Candidate> suggest(UUID documentId, String ownerUsername) {
        var document = documents.findDocument(documentId)
                .filter(found -> found.createdBy().equals(ownerUsername))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        var latestVersion = documents.findVersions(documentId).stream()
                .max(Comparator.comparingInt(version -> version.versionNumber()))
                .orElseThrow(() -> new IllegalArgumentException("rule document has no version"));
        if (latestVersion.status() != ProcessingStatus.READY) {
            throw new IllegalArgumentException("rule document is not ready for metadata suggestions");
        }
        return catalogMatching.findExactCandidates(document.title());
    }
}
