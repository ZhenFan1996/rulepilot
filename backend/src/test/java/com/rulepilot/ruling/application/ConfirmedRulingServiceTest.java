package com.rulepilot.ruling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.RuleDataVersion;
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
    private final MutableRuleDataVersion ruleDataVersion = new MutableRuleDataVersion();

    @Test
    void savesConfirmedRulingWithTrustedVersionScopedCitation() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService service = service(repository, Set.of(expansionId), List.of(evidence(chunkId)));

        ConfirmedRuling ruling = service.confirm(
                versionId, Set.of(expansionId), "  How   are COINS scored? ",
                "Coins score one point.", "Each remaining coin contributes one point.",
                List.of(chunkId), List.of("Only remaining coins count."), RulingConfidence.HIGH, "alice");

        assertThat(ruling.status()).isEqualTo(RulingStatus.CONFIRMED);
        assertThat(ruling.normalizedQuestion()).isEqualTo("how are coins scored?");
        assertThat(ruling.official()).isFalse();
        assertThat(ruling.version()).isZero();
        assertThat(ruleDataVersion.current(versionId)).isEqualTo(2);
        assertThat(ruling.applicability().expansionIds()).containsExactly(expansionId);
        assertThat(ruling.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(chunkId);
            assertThat(citation.excerpt()).isEqualTo("Each coin is worth one point.");
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(service.get(ruling.id(), "alice")).isEqualTo(ruling);
        assertThat(service.find(versionId, Set.of(expansionId), "HOW ARE COINS SCORED?", "alice"))
                .hasValueSatisfying(answer -> {
                    assertThat(answer.rulingId()).isEqualTo(ruling.id());
                    assertThat(answer.citations()).hasSize(1);
                });
        assertThat(service.find(versionId, Set.of(), "How are coins scored?", "alice")).isEmpty();
        assertThat(service.find(versionId, Set.of(expansionId), "How are coins scored?", "bob")).isEmpty();
    }

    @Test
    void preservesLongFormConfirmedContentBecauseThePersistenceColumnsAreText() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService service = service(repository, Set.of(), List.of(evidence(chunkId)));
        String question = "How does this detailed situation resolve? " + "context ".repeat(350);
        String verdict = "Resolve the complete situation as follows. " + "verdict detail ".repeat(180);
        String explanation = "First paragraph.\n\n" + "Evidence-backed explanation. ".repeat(900);
        String exception = "This exception remains relevant when the alternate condition applies. "
                + "exception detail ".repeat(140);

        ConfirmedRuling ruling = service.confirm(
                versionId,
                Set.of(),
                question,
                verdict,
                explanation,
                List.of(chunkId),
                List.of(exception),
                RulingConfidence.HIGH,
                "alice");

        assertThat(ruling.originalQuestion()).isEqualTo(question.strip()).hasSizeGreaterThan(2_000);
        assertThat(ruling.shortVerdict()).isEqualTo(verdict.strip()).hasSizeGreaterThan(2_000);
        assertThat(ruling.explanation()).isEqualTo(explanation.strip()).contains("\n\n").hasSizeGreaterThan(20_000);
        assertThat(ruling.exceptions().getFirst()).isEqualTo(exception.strip()).hasSizeGreaterThan(2_000);
    }

    @Test
    void rejectsMissingEvidenceAndDuplicateActiveScope() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService missingEvidence = service(repository, Set.of(), List.of());

        assertThatThrownBy(() -> missingEvidence.confirm(
                        versionId, Set.of(), "How is scoring resolved?",
                        "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                        RulingConfidence.MEDIUM, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");

        ConfirmedRulingService service = service(repository, Set.of(), List.of(evidence(chunkId)));
        service.confirm(
                versionId, Set.of(), "How is scoring resolved?",
                "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                RulingConfidence.MEDIUM, "alice");

        assertThatThrownBy(() -> service.confirm(
                        versionId, Set.of(), "  HOW IS SCORING RESOLVED? ",
                        "One point.", "Each coin scores.", List.of(chunkId), List.of(),
                        RulingConfidence.MEDIUM, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        assertThat(ruleDataVersion.current(versionId)).isEqualTo(2);
    }

    @Test
    void revisesAtExpectedVersionAndRejectsStaleEditor() {
        InMemoryRulings repository = new InMemoryRulings();
        ConfirmedRulingService service = service(repository, Set.of(), List.of(evidence(chunkId)));
        ConfirmedRuling created = service.confirm(
                versionId, Set.of(), "How are coins scored?", "One point.", "Each coin scores.",
                List.of(chunkId), List.of(), RulingConfidence.MEDIUM, "alice");

        ConfirmedRuling revised = service.revise(
                created.id(), 0, "One point per remaining coin.", "Count coins after play ends.",
                List.of(chunkId), List.of("Spent coins do not count."), RulingConfidence.HIGH, "alice");

        assertThat(revised.version()).isEqualTo(1);
        assertThat(ruleDataVersion.current(versionId)).isEqualTo(3);
        assertThat(revised.shortVerdict()).contains("remaining coin");
        assertThatThrownBy(() -> service.revise(
                        created.id(), 0, "Stale edit", "Stale edit", List.of(chunkId), List.of(),
                        RulingConfidence.LOW, "alice"))
                .isInstanceOf(RulingVersionConflictException.class)
                .extracting("currentVersion")
                .isEqualTo(1L);
        assertThat(ruleDataVersion.current(versionId)).isEqualTo(3);
    }

    private ConfirmedRulingService service(
            ConfirmedRulingRepository repository,
            Set<UUID> compatibleExpansions,
            List<RuleEvidenceHit> evidenceHits) {
        CatalogEditionLookup catalog = id -> Optional.of(new CatalogEditionLookup.EditionReference(
                editionId, gameId, "Example Game", "Base", "en", compatibleExpansions));
        DocumentVersionScopeLookup documents = id -> Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, editionId, "READY", "owner"));
        RuleEvidenceLookup evidence = (documentVersionId, chunkIds) -> evidenceHits.stream()
                .filter(hit -> documentVersionId.equals(hit.documentVersionId()))
                .filter(hit -> chunkIds.contains(hit.chunkId()))
                .toList();
        return new ConfirmedRulingService(catalog, documents, evidence, repository, ruleDataVersion);
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
        public Optional<ConfirmedRuling> findConfirmed(
                UUID editionId,
                UUID documentVersionId,
                String expansionSetHash,
                String normalizedQuestionHash,
                String createdBy) {
            return values.stream().filter(ruling -> ruling.applicability().editionId().equals(editionId)
                    && ruling.applicability().documentVersionId().equals(documentVersionId)
                    && ruling.applicability().expansionSetHash().equals(expansionSetHash)
                    && ruling.normalizedQuestionHash().equals(normalizedQuestionHash)
                    && ruling.createdBy().equals(createdBy)
                    && ruling.status() == RulingStatus.CONFIRMED).findFirst();
        }

        @Override
        public ConfirmedRuling update(ConfirmedRuling ruling, long expectedVersion) {
            ConfirmedRuling current = find(ruling.id()).orElseThrow();
            if (current.version() != expectedVersion) {
                throw new RulingVersionConflictException(current.version());
            }
            values.set(values.indexOf(current), ruling);
            return ruling;
        }

        @Override
        public boolean existsConfirmed(RulingApplicability applicability, String normalizedQuestionHash) {
            return values.stream().anyMatch(ruling -> ruling.status() == RulingStatus.CONFIRMED
                    && ruling.applicability().equals(applicability)
                    && ruling.normalizedQuestionHash().equals(normalizedQuestionHash));
        }
    }

    private static final class MutableRuleDataVersion implements RuleDataVersion {
        private long value = 1;

        @Override
        public long current(UUID documentVersionId) {
            return value;
        }

        @Override
        public long increment(UUID documentVersionId) {
            return ++value;
        }
    }
}
