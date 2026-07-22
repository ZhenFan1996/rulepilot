package com.rulepilot.document.application;

import com.rulepilot.document.DocumentVersionScopeLookup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class DocumentVersionScopeLookupService implements DocumentVersionScopeLookup {

    private final RuleDocumentRepository repository;

    DocumentVersionScopeLookupService(RuleDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public java.util.Optional<VersionScope> findVersion(java.util.UUID documentVersionId) {
        return repository.findVersion(documentVersionId).flatMap(version -> repository.findDocument(version.documentId())
                .map(document -> new VersionScope(
                        version.id(),
                        document.gameEditionId(),
                        version.status().name(),
                        document.createdBy(),
                        document.title())));
    }
}
