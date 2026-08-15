package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.CatalogEditionLookup.EditionReference;
import com.rulepilot.document.application.OfficialRulebookImportIdentity;
import com.rulepilot.document.application.OfficialRulebookImportIdentityException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRulebookImportExceptionHandlerTest {

    @Test
    void exposesAConflictReviewWithoutFlatteningUnknownSourceFacts() {
        UUID editionId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        var selected = new EditionReference(
                editionId, gameId, "Opaque Game", "Opaque Edition", "und", Set.of());
        var source = new OfficialRulebookImportIdentity.SourceClaim(
                editionId, null, null, false);
        var review = OfficialRulebookImportIdentity.review(
                selected, source, Optional.of(selected), java.util.List.of());

        var response = new OfficialRulebookImportExceptionHandler()
                .handleIdentity(OfficialRulebookImportIdentityException.confirmationRequired(review));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "RULEBOOK_CONFIRMATION_REQUIRED")
                .containsEntry("identityReview", review);
        assertThat(review.issues()).contains(
                OfficialRulebookImportIdentity.Issue.SOURCE_EDITION_UNKNOWN,
                OfficialRulebookImportIdentity.Issue.CATALOG_LANGUAGE_UNKNOWN,
                OfficialRulebookImportIdentity.Issue.SOURCE_LANGUAGE_UNKNOWN);
    }
}
