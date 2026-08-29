package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceCoverage;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.AnswerEvidencePolicy;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.UUID;

/** Decides whether retrieved source evidence is safe to give to the answer model. */
final class AnswerEvidenceAdmissionGate {

    private final AnswerPublicationValidator publicationValidator;

    AnswerEvidenceAdmissionGate(AnswerPublicationValidator publicationValidator) {
        this.publicationValidator = publicationValidator;
    }

    Admission admit(UUID documentVersionId, AnswerEvidenceRetriever.Result retrievalResult) {
        if (retrievalResult.state() == AnswerEvidenceRetriever.State.CONFLICTING) {
            return Admission.rejected(AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突，无法可靠回答。");
        }
        if (retrievalResult.state() == AnswerEvidenceRetriever.State.UNAVAILABLE) {
            return Admission.rejected(AnswerStatus.INVALID_MODEL_OUTPUT, "规则检索暂时不可用，尚未生成答案。");
        }
        EvidenceCoverage coverage = retrievalResult.state() == AnswerEvidenceRetriever.State.PARTIAL
                ? EvidenceCoverage.PARTIAL
                : EvidenceCoverage.COMPLETE;
        List<HybridEvidenceHit> evidence = retrievalResult.evidence().stream()
                .filter(hit -> !AnswerEvidencePolicy.isVisualPlaceholder(hit))
                .toList();
        if (evidence.isEmpty()) {
            return Admission.rejected(
                    AnswerStatus.INSUFFICIENT_EVIDENCE,
                    coverage == EvidenceCoverage.PARTIAL
                            ? "部分规则检索来源暂时不可用，现有可用来源没有找到足够的可引用依据。"
                            : "没有找到可引用的规则依据。");
        }
        var verification = publicationValidator.verifySources(documentVersionId, evidence);
        if (verification.status() == VerificationStatus.VERSION_CONFLICT) {
            return Admission.rejected(AnswerStatus.VERSION_CONFLICT, "检索证据与当前规则版本不一致。");
        }
        if (!verification.verified()) {
            return Admission.rejected(AnswerStatus.INSUFFICIENT_EVIDENCE, "检索证据存在冲突或不足，无法可靠回答。");
        }
        return Admission.ready(evidence, coverage);
    }

    record Admission(
            List<HybridEvidenceHit> evidence,
            EvidenceCoverage evidenceCoverage,
            AnswerStatus failureStatus,
            String failureMessage) {

        static Admission ready(List<HybridEvidenceHit> evidence, EvidenceCoverage evidenceCoverage) {
            return new Admission(List.copyOf(evidence), evidenceCoverage, null, null);
        }

        static Admission rejected(AnswerStatus failureStatus, String failureMessage) {
            return new Admission(List.of(), EvidenceCoverage.COMPLETE, failureStatus, failureMessage);
        }

        boolean ready() {
            return failureStatus == null;
        }
    }
}
