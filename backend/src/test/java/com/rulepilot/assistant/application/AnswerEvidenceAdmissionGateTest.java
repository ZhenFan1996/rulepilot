package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.Verification;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AnswerEvidenceAdmissionGateTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void rejectsConflictingRetrievalBeforeCallingTheSourceVerifier() {
        AtomicBoolean verified = new AtomicBoolean();
        AnswerEvidenceAdmissionGate.Admission admission = gate(request -> {
                    verified.set(true);
                    return verified();
                })
                .admit(documentVersionId, result(List.of(), AnswerEvidenceRetriever.State.CONFLICTING));

        assertThat(admission.ready()).isFalse();
        assertThat(admission.failureStatus()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(admission.failureMessage()).isEqualTo("检索证据存在冲突，无法可靠回答。");
        assertThat(verified).isFalse();
    }

    @Test
    void rejectsUnavailableAndEmptyRetrievalBeforeCallingTheSourceVerifier() {
        AtomicBoolean verified = new AtomicBoolean();
        AnswerEvidenceAdmissionGate gate = gate(request -> {
            verified.set(true);
            return verified();
        });

        AnswerEvidenceAdmissionGate.Admission unavailable = gate.admit(
                documentVersionId, result(List.of(), AnswerEvidenceRetriever.State.UNAVAILABLE));
        AnswerEvidenceAdmissionGate.Admission empty = gate.admit(
                documentVersionId, result(List.of(), AnswerEvidenceRetriever.State.READY));

        assertThat(unavailable.failureStatus()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(empty.failureStatus()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(empty.failureMessage()).isEqualTo("没有找到可引用的规则依据。");
        assertThat(verified).isFalse();
    }

    @Test
    void preservesVersionConflictAndGenericSourceVerificationFailures() {
        AnswerEvidenceAdmissionGate versionConflictGate = gate(request ->
                new Verification(VerificationStatus.VERSION_CONFLICT, List.of("VERSION_CONFLICT")));
        AnswerEvidenceAdmissionGate genericFailureGate = gate(request ->
                new Verification(VerificationStatus.SOURCE_CONFLICT, List.of("SOURCE_CONFLICT")));

        AnswerEvidenceAdmissionGate.Admission versionConflict = versionConflictGate.admit(
                documentVersionId, result(List.of(evidence()), AnswerEvidenceRetriever.State.READY));
        AnswerEvidenceAdmissionGate.Admission genericFailure = genericFailureGate.admit(
                documentVersionId, result(List.of(evidence()), AnswerEvidenceRetriever.State.READY));

        assertThat(versionConflict.failureStatus()).isEqualTo(AnswerStatus.VERSION_CONFLICT);
        assertThat(versionConflict.failureMessage()).isEqualTo("检索证据与当前规则版本不一致。");
        assertThat(genericFailure.failureStatus()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(genericFailure.failureMessage()).isEqualTo("检索证据存在冲突或不足，无法可靠回答。");
    }

    @Test
    void admitsOnlyVerifiedCurrentVersionEvidence() {
        HybridEvidenceHit evidence = evidence();

        AnswerEvidenceAdmissionGate.Admission admission = gate(request -> verified())
                .admit(documentVersionId, result(List.of(evidence), AnswerEvidenceRetriever.State.READY));

        assertThat(admission.ready()).isTrue();
        assertThat(admission.evidence()).containsExactly(evidence);
        assertThat(admission.failureStatus()).isNull();
    }

    private AnswerEvidenceAdmissionGate gate(EvidenceVerifier verifier) {
        return new AnswerEvidenceAdmissionGate(new AnswerPublicationValidator(verifier));
    }

    private AnswerEvidenceRetriever.Result result(
            List<HybridEvidenceHit> evidence, AnswerEvidenceRetriever.State state) {
        return new AnswerEvidenceRetriever.Result(evidence, state);
    }

    private HybridEvidenceHit evidence() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), documentVersionId, "RULES", "Rule", "Rule text", 2, 2, 0.9);
        return new HybridEvidenceHit(source, 0.9, 1, null, false);
    }

    private Verification verified() {
        return new Verification(VerificationStatus.VERIFIED, List.of());
    }
}
