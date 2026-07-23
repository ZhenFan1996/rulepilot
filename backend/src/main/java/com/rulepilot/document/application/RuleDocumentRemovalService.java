package com.rulepilot.document.application;

import com.rulepilot.document.domain.DocumentVersion;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes a user's local rulebook together with derived local material, never its publisher-hosted source. */
@Service
@Profile("!test")
public class RuleDocumentRemovalService {

    private final RuleDocumentRepository documents;
    private final DocumentStorage storage;

    public RuleDocumentRemovalService(
            RuleDocumentRepository documents,
            DocumentStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    @Transactional
    public void removeOwned(UUID documentId, String username) {
        var document = documents.findDocument(documentId)
                .filter(found -> found.createdBy().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        List<DocumentVersion> versions = documents.findVersions(document.id());

        LinkedHashSet<String> objectKeys = new LinkedHashSet<>();
        versions.forEach(version -> {
            objectKeys.add(version.objectKey());
            documents.findAllPageImages(version.id()).forEach(image -> objectKeys.add(image.objectKey()));
        });
        documents.deleteDocument(document.id());
        objectKeys.forEach(storage::delete);
    }
}
