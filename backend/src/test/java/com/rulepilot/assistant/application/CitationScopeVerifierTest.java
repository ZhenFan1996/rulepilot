package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationScopeVerifierTest {

    private final CitationScopeVerifier verifier = new CitationScopeVerifier();
    private final UUID versionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void verifiesClaimsCitedFromTheRequestedVersion() {
        var result = verifier.verify(new VerificationRequest(
                versionId,
                List.of(source(chunkId, versionId, "Each coin scores one point.")),
                List.of(new EvidenceClaim("Coins score one point.", List.of(chunkId)))));

        assertThat(result.verified()).isTrue();
        assertThat(result.issueCodes()).isEmpty();
    }

    @Test
    void reportsVersionConflictBeforeClaimsCanBeTrusted() {
        var result = verifier.verify(new VerificationRequest(
                versionId,
                List.of(source(chunkId, UUID.randomUUID(), "Each coin scores one point.")),
                List.of()));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERSION_CONFLICT);
        assertThat(result.issueCodes()).containsExactly(CitationScopeVerifier.VERSION_MISMATCH);
    }

    @Test
    void reportsConflictingSnapshotsForTheSameSourceIdentity() {
        var result = verifier.verify(new VerificationRequest(
                versionId,
                List.of(
                        source(chunkId, versionId, "Each coin scores one point."),
                        source(chunkId, versionId, "Each coin scores two points.")),
                List.of(new EvidenceClaim("Coins have a score.", List.of(chunkId)))));

        assertThat(result.status()).isEqualTo(VerificationStatus.SOURCE_CONFLICT);
        assertThat(result.issueCodes()).contains(
                CitationScopeVerifier.SOURCE_SNAPSHOT_CONFLICT,
                CitationScopeVerifier.CITATION_OUTSIDE_EVIDENCE);
    }

    @Test
    void reportsMissingAndOutOfScopeClaimEvidence() {
        var result = verifier.verify(new VerificationRequest(
                versionId,
                List.of(source(chunkId, versionId, "Each coin scores one point.")),
                List.of(
                        new EvidenceClaim("Unsupported claim.", List.of()),
                        new EvidenceClaim("Unknown source.", List.of(UUID.randomUUID())))));

        assertThat(result.status()).isEqualTo(VerificationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.issueCodes()).containsExactly(
                CitationScopeVerifier.CLAIM_WITHOUT_CITATION,
                CitationScopeVerifier.CITATION_OUTSIDE_EVIDENCE);
    }

    private EvidenceSource source(UUID id, UUID version, String excerpt) {
        return new EvidenceSource(id, version, "SCORING", excerpt, 8, 8);
    }
}
