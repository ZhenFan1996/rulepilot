package com.rulepilot.teaching.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGamePresentationLookup.Presentation;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Joins catalog identity only after a teaching plan has been selected for presentation. */
@Service
@Profile("!test")
public class TeachingPlanCatalogPresentationService {

    private final DocumentVersionScopeLookup documents;
    private final CatalogGamePresentationLookup catalog;

    public TeachingPlanCatalogPresentationService(
            DocumentVersionScopeLookup documents, CatalogGamePresentationLookup catalog) {
        this.documents = documents;
        this.catalog = catalog;
    }

    public Optional<Presentation> findForOwnedPlan(TeachingPlan plan) {
        if (plan == null) return Optional.empty();
        return documents.findVersion(plan.documentVersionId())
                .filter(version -> plan.createdBy().equals(version.createdBy()))
                .map(DocumentVersionScopeLookup.VersionScope::editionId)
                .flatMap(catalog::findByEdition);
    }
}
