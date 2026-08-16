package com.rulepilot.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateClaimTest {

    @Test
    void allowsOnlyClaimsSupportedByTheObservationCapability() {
        CandidateObservation taxonomy = new CandidateObservation(
                "bgg-41-mechanics",
                41,
                CandidateObservation.Kind.TAXONOMY,
                "mechanics",
                "Opaque Classification",
                List.of());

        CandidateClaim classification = new CandidateClaim(
                41,
                "mechanics",
                CandidateClaim.Type.TAXONOMY_CLASSIFICATION,
                null,
                CandidateClaim.Relation.OBSERVED,
                "BGG classifies this candidate with Opaque Classification.",
                List.of(taxonomy));

        assertThat(classification.evidence()).containsExactly(taxonomy);
        assertThatThrownBy(() -> new CandidateClaim(
                        41,
                        "mechanics",
                        CandidateClaim.Type.RULE_PROCEDURE,
                        null,
                        CandidateClaim.Relation.OBSERVED,
                        "Place a fixed quantity into a specific area.",
                        List.of(taxonomy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot support");
    }

    @Test
    void rejectsCrossCandidateEvidenceAndRequiresEvidenceForDefiniteClaims() {
        CandidateObservation otherCandidate = new CandidateObservation(
                "bgg-52-duration",
                52,
                CandidateObservation.Kind.STRUCTURED_METADATA,
                "durationMinutes",
                "45..75",
                List.of());

        assertThatThrownBy(() -> new CandidateClaim(
                        51,
                        "durationMinutes",
                        CandidateClaim.Type.CONSTRAINT_FIT,
                        ConstraintRange.Strength.HARD,
                        CandidateClaim.Relation.SATISFIED,
                        "The duration is inside the requested range.",
                        List.of(otherCandidate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same candidate");
        assertThatThrownBy(() -> new CandidateClaim(
                        51,
                        "durationMinutes",
                        CandidateClaim.Type.CONSTRAINT_FIT,
                        ConstraintRange.Strength.HARD,
                        CandidateClaim.Relation.SATISFIED,
                        "The duration is inside the requested range.",
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void representsUnknownWithoutPretendingThatRelatedMaterialEntailsTheAnswer() {
        CandidateClaim unknown = new CandidateClaim(
                61,
                "durationMinutes",
                CandidateClaim.Type.CONSTRAINT_FIT,
                ConstraintRange.Strength.SOFT,
                CandidateClaim.Relation.UNKNOWN,
                "The available candidate facts do not establish this constraint.",
                List.of());

        assertThat(unknown.evidence()).isEmpty();
        assertThat(unknown.strength()).isEqualTo(ConstraintRange.Strength.SOFT);
        assertThat(unknown.relation()).isEqualTo(CandidateClaim.Relation.UNKNOWN);
    }

    @Test
    void requiresAttributedReportsToCarryARealSourceIndex() {
        assertThatThrownBy(() -> new CandidateObservation(
                        "web-71-1",
                        71,
                        CandidateObservation.Kind.ATTRIBUTED_REPORT,
                        "reportedExperience",
                        "A bounded attributed observation.",
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }
}
