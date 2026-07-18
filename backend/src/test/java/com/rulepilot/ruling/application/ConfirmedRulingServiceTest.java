package com.rulepilot.ruling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.domain.ConfirmedRuling;
import com.rulepilot.ruling.domain.RulingApplicability;
import com.rulepilot.ruling.domain.RulingConfidence;
import com.rulepilot.ruling.domain.RulingStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmedRulingServiceTest {

    private final UUID gameId = UUID.randomUUID();
    private final UUID editionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID expansionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void savesConfirmedRulingWithTrustedVersionScopedCitation() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService service = service(repository, Set.of(expansionId), List.of(evidence(chunkId)));

        ConfirmedRuling ruling = service.confirm(
                editionId, versionId, Set.of(expansionId), "  How   are COINS scored? ",
                "Coins score one point.", "Each remaining coin contributes one point.",
                List.of(chunkId), List.of("Only remaining coins count."), RulingConfidence.HIGH, "alice");

        assertThat(ruling.status()).isEqualTo(RulingStatus.CONFIRMED);
        assertThat(ruling.normalizedQuestion()).isEqualTo("how are coins scored?");
        assertThat(ruling.official()).isFalse();
        assertThat(ruling.version()).isZero();
        assertThat(ruling.applicability().expansionIds()).containsExactly(expansionId);
        assertThat(ruling.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(chunkId);
            assertThat(citation.excerpt()).isEqualTo("Each coin is worth one point.");
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(service.get(ruling.id(), "alice")).isEqualTo(ruling);
    }

    @Test
    void rejectsMissingEvidenceAndDuplicateActiveScope() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService missingEvidence = service(repository, Set.of(), List.of());

        assertThatThrownBy(() -> missingEvidence.confirm(
                        editionId, versionId, Set.of(), "How is scoring resolved?",
                        "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                        RulingConfidence.MEDIUM, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");

        ConfirmedRulingService service = service(repository, Set.of(), List.of(evidence(chunkId)));
        service.confirm(
                editionId, versionId, Set.of(), "How is scoring resolved?",
                "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                RulingConfidence.MEDIUM, "alice");

        assertThatThrownBy(() -> service.confirm(
                        editionId, versionId, Set.of(), "  HOW IS SCORING RESOLVED? ",
                        "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                        RulingConfidence.MEDIUM, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    private ConfirmedRulingService service(
            ConfirmedRulingRepository repository,
            Set<UUID> compatibleExpansions,
            List<RuleEvidenceHit> evidenceHits) {
        CatalogEditionLookup catalog = id -> Optional.of(new CatalogEditionLookup.EditionReference(
                editionId, gameId, "Base", "en", compatibleExpansions));
        DocumentVersionScopeLookup documents = id -> Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, editionId, "READY"));
        RuleEvidenceLookup evidence = (documentVersionId, chunkIds) -> evidenceHits.stream()
                .filter(hit -> documentVersionId.equals(hit.documentVersionId()))
                .filter(hit -> chunkIds.contains(hit.chunkId()))
                .toList();
        return new ConfirmedRulingService(catalog, documents, evidence, repository);
    }

    private RuleEvidenceHit evidence(UUID id) {
        return new RuleEvidenceHit(
                id, versionId, "SCORING", "Scoring", "Each coin is worth one point.", 8, 8, 1.0);
    }

    private static final class InMemoryRulings implements ConfirmedRulingRepository {
        private final List<ConfirmedRuling> values = new ArrayList<>();

        @Override
        public ConfirmedRuling save(ConfirmedRuling ruling) {
            values.add(ruling);
            return ruling;
        }

        @Override
        public Optional<ConfirmedRuling> find(UUID rulingId) {
            return values.stream().filter(ruling -> ruling.id().equals(rulingId)).findFirst();
        }

        @Override
        public boolean existsConfirmed(RulingApplicability applicability, String normalizedQuestionHash) {
            return values.stream().anyMatch(ruling -> ruling.status() == RulingStatus.CONFIRMED
                    && ruling.applicability().equals(applicability)
                    && ruling.normalizedQuestionHash().equals(normalizedQuestionHash));
        }
    }
}
