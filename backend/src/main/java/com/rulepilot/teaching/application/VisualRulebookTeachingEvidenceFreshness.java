package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RulebookTeachingEvidenceFreshness;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Detects visual-derived evidence whose persisted contract is older than the current teaching schema. */
@Component
@Profile("!test")
final class VisualRulebookTeachingEvidenceFreshness implements RulebookTeachingEvidenceFreshness {

    private final DocumentProcessing documents;
    private final DocumentVersionScopeLookup documentScopes;
    private final VisualRulebookPageFacts visualFacts;
    private final AssistantRuns runs;

    VisualRulebookTeachingEvidenceFreshness(
            DocumentProcessing documents,
            DocumentVersionScopeLookup documentScopes,
            VisualRulebookPageFacts visualFacts,
            AssistantRuns runs) {
        this.documents = documents;
        this.documentScopes = documentScopes;
        this.visualFacts = visualFacts;
        this.runs = runs;
    }

    @Override
    public boolean requiresRefresh(UUID documentVersionId, UUID preparationRunId, String ownerUsername) {
        if (documentVersionId == null || ownerUsername == null || ownerUsername.isBlank()) return false;
        String owner = ownerUsername.strip();
        var scope = documentScopes.findVersion(documentVersionId)
                .filter(found -> owner.equals(found.createdBy()))
                .filter(found -> "READY".equals(found.processingStatus()));
        if (scope.isEmpty()) return false;
        if (preparationRunId != null && runs.findOwned(preparationRunId, owner)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal())
                .isPresent()) {
            return false;
        }
        var pages = documents.pages(documentVersionId);
        boolean visualOnly = !pages.isEmpty()
                && pages.stream().allMatch(page -> page.text() == null || page.text().isBlank());
        if (!visualOnly) return false;
        var requestedPages = pages.stream()
                .map(DocumentProcessing.PageView::pageNumber)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var cached = visualFacts.find(documentVersionId, requestedPages);
        return !VisualRulebookCatalogPolicy.missingPages(requestedPages, cached).isEmpty();
    }
}
