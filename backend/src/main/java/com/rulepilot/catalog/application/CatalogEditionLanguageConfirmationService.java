package com.rulepilot.catalog.application;

import com.rulepilot.catalog.CatalogEditionLanguageConfirmation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class CatalogEditionLanguageConfirmationService implements CatalogEditionLanguageConfirmation {

    private final CatalogRepository repository;

    CatalogEditionLanguageConfirmationService(CatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean confirmIfUnknown(java.util.UUID editionId, String language) {
        if (editionId == null) throw new IllegalArgumentException("catalog edition is required");
        var current = repository.findEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("catalog edition does not exist"));
        var confirmed = current.confirmLanguageIfUnknown(language);
        if (confirmed == current) return false;
        return repository.confirmEditionLanguageIfUnknown(editionId, confirmed.language());
    }
}
