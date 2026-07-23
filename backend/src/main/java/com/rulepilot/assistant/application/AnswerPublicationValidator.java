package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Keeps the answer-publication boundary strict after untrusted model generation. */
final class AnswerPublicationValidator {

    private final EvidenceVerifier evidenceVerifier;

    AnswerPublicationValidator(EvidenceVerifier evidenceVerifier) {
        this.evidenceVerifier = evidenceVerifier;
    }

    EvidenceVerifier.Verification verifySources(UUID versionId, List<HybridEvidenceHit> evidence) {
        return evidenceVerifier.verify(new EvidenceVerifier.VerificationRequest(
                versionId, evidence.stream().map(AnswerPublicationValidator::toVerifierEvidence).toList(), List.of()));
    }

    StructuredRuleAnswer publish(UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence) {
        if (draft.shortVerdict() == null || draft.shortVerdict().isBlank() || draft.shortVerdict().length() > 240
                || draft.explanation() == null || draft.explanation().isBlank() || draft.explanation().length() > 1500
                || draft.citationIds().isEmpty() || draft.exceptions().size() > 6
                || draft.exceptions().stream()
                        .anyMatch(exception -> exception == null || exception.isBlank() || exception.length() > 400)) {
            throw new IllegalArgumentException("model draft is incomplete");
        }
        String completeAnswer = draft.shortVerdict() + "\n" + draft.explanation() + "\n"
                + String.join("\n", draft.exceptions());
        if (AnswerDraftSafetyPolicy.containsInternalEvidenceReference(completeAnswer)) {
            throw new IllegalArgumentException("player-facing answer contains internal evidence references");
        }
        var verification = evidenceVerifier.verify(new EvidenceVerifier.VerificationRequest(
                versionId,
                evidence.stream().map(AnswerPublicationValidator::toVerifierEvidence).toList(),
                List.of(new EvidenceClaim(completeAnswer, draft.citationIds()))));
        if (!verification.verified()) {
            throw new IllegalArgumentException("answer evidence did not pass policy verification");
        }
        Map<UUID, HybridEvidenceHit> allowed = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        hit -> hit.evidence().chunkId(), Function.identity(), (first, duplicate) -> first));
        List<RuleCitation> citations = draft.citationIds().stream().distinct().map(id -> {
            HybridEvidenceHit hit = allowed.get(id);
            if (hit == null || !versionId.equals(hit.evidence().documentVersionId())) {
                throw new IllegalArgumentException("model cited evidence outside the allowed scope");
            }
            var source = hit.evidence();
            return new RuleCitation(
                    source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                    source.excerpt(), source.pageFrom(), source.pageTo());
        }).toList();
        AnswerConfidence confidence = AnswerConfidence.valueOf(draft.confidence().toUpperCase(Locale.ROOT));
        return new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, draft.shortVerdict(), draft.explanation(), citations,
                draft.exceptions(), confidence, false, null, null, null);
    }

    private static EvidenceSource toVerifierEvidence(HybridEvidenceHit hit) {
        var source = hit.evidence();
        return new EvidenceSource(
                source.chunkId(), source.documentVersionId(), source.sectionType(), source.excerpt(),
                source.pageFrom(), source.pageTo());
    }
}
