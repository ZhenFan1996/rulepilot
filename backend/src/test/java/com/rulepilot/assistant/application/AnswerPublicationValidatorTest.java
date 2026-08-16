package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.Verification;
import com.rulepilot.assistant.EvidenceVerifier.VerificationStatus;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.retrieval.VisualTranscribedRuleEvidence;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerPublicationValidatorTest {

    private final UUID versionId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    @Test
    void publishesOnlyCurrentVersionCitationsAfterVerifiedClaims() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());

        var answer = validator.publish(versionId, draft(List.of(chunkId), "HIGH"), List.of(evidence(versionId)));

        assertThat(answer.confidence()).isEqualTo(AnswerConfidence.HIGH);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(chunkId);
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.pageFrom()).isEqualTo(4);
        });
    }

    @Test
    void keepsTheExplicitGroundedApplicationBasisWithTheValidatedAnswer() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        ModelDraft grounded = new ModelDraft(
                true,
                null,
                "若你描述的条件成立，则按该规则执行。",
                "规则先要求满足列出的条件；你已说明条件成立，因此套用该结果。",
                List.of(chunkId),
                List.of(),
                "MEDIUM",
                "GROUNDED_APPLICATION");

        var answer = validator.publish(versionId, grounded, List.of(evidence(versionId)));

        assertThat(answer.answerBasis()).isEqualTo(AnswerBasis.GROUNDED_APPLICATION);
    }

    @Test
    void rejectsAnUnknownModelAnswerBasisRatherThanPublishingIt() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        ModelDraft unknownBasis = new ModelDraft(
                true,
                null,
                "可以执行这个行动。",
                "规则满足列出的条件后允许执行该行动。",
                List.of(chunkId),
                List.of(),
                "MEDIUM",
                "MODEL_MEMORY");

        assertThatThrownBy(() -> validator.publish(versionId, unknownBasis, List.of(evidence(versionId))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDraftsThatLeakInternalEvidenceReferencesToPlayers() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        ModelDraft leaking = new ModelDraft(
                "按规则执行。", "请参阅 chunk " + chunkId + "。", List.of(chunkId), List.of(), "HIGH");

        assertThatThrownBy(() -> validator.publish(versionId, leaking, List.of(evidence(versionId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal evidence");
    }

    @Test
    void rejectsTheKnownShortEvidenceHandleObservedInPlayerFacingProse() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        String shortHandle = chunkId.toString().substring(0, 8);
        ModelDraft leaking = new ModelDraft(
                "按规则执行。", "来源 " + shortHandle + " 说明应这样处理。", List.of(chunkId), List.of(), "HIGH");

        assertThatThrownBy(() -> validator.publish(versionId, leaking, List.of(evidence(versionId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal evidence");
    }

    @Test
    void rejectsInternalProtocolReferencesInsideStructuredPlayerFacingDetails() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        RuleWalkthroughStep leakingStep = new RuleWalkthroughStep(
                "Move the cobalt spindle.",
                "Then run repairRuleTimingResolutions for " + UUID.randomUUID() + ".",
                WalkthroughOrderBasis.RULE_ORDER,
                List.of(chunkId));

        assertThatThrownBy(() -> validator.publish(
                        versionId,
                        draft(List.of(chunkId), "HIGH"),
                        List.of(evidence(versionId)),
                        List.of(),
                        List.of(),
                        List.of(leakingStep)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal evidence");
    }

    @Test
    void publishesOnlyTheReadableRuleFactsFromAVisualEvidenceEnvelope() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        HybridEvidenceHit visualEvidence = evidence(
                versionId,
                VisualTranscribedRuleEvidence.render("每张已完成的目标卡得 2 分。"));

        var answer = validator.publish(versionId, draft(List.of(chunkId), "HIGH"), List.of(visualEvidence));

        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.excerpt()).isEqualTo("每张已完成的目标卡得 2 分。");
            assertThat(citation.excerpt()).doesNotContain("Visual-transcribed", "Do not derive", "Visible rule facts");
        });
    }

    @Test
    void preservesLegacyAndStructuredExceptionDetailsInsteadOfForcingAWholeDraftRewrite() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());
        ModelDraft draft = new ModelDraft(
                "按规则执行。",
                "满足列出的条件后执行该规则。",
                List.of(chunkId),
                List.of("扩展内容可能另有例外。"),
                "HIGH");
        RuleExceptionClause structured = new RuleExceptionClause(
                "效果处于激活状态时",
                "不能再创建同名效果。",
                List.of(chunkId));

        var answer = validator.publish(
                versionId,
                draft,
                List.of(evidence(versionId)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(structured));

        assertThat(answer.exceptions()).containsExactly("扩展内容可能另有例外。");
        assertThat(answer.exceptionClauses()).containsExactly(structured);
    }

    @Test
    void rejectsCitationsOutsideTheRetrievedCurrentVersionScope() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(verified());

        assertThatThrownBy(() -> validator.publish(
                        versionId, draft(List.of(UUID.randomUUID()), "HIGH"), List.of(evidence(versionId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed scope");
        assertThatThrownBy(() -> validator.publish(
                        versionId, draft(List.of(chunkId), "HIGH"), List.of(evidence(UUID.randomUUID()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed scope");
    }

    @Test
    void refusesPublicationWhenEvidenceVerificationDoesNotAcceptTheClaim() {
        AnswerPublicationValidator validator = new AnswerPublicationValidator(request ->
                new Verification(VerificationStatus.INSUFFICIENT_EVIDENCE, List.of("CLAIM_UNSUPPORTED")));

        assertThatThrownBy(() -> validator.publish(versionId, draft(List.of(chunkId), "HIGH"), List.of(evidence(versionId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not pass policy verification");
    }

    private EvidenceVerifier verified() {
        return request -> new Verification(VerificationStatus.VERIFIED, List.of());
    }

    private ModelDraft draft(List<UUID> citations, String confidence) {
        return new ModelDraft(
                "按规则执行。", "满足列出的条件后执行该规则。", citations, List.of("扩展规则可能另有说明。"), confidence);
    }

    private HybridEvidenceHit evidence(UUID sourceVersionId) {
        return evidence(sourceVersionId, "满足列出的条件后执行该规则。");
    }

    private HybridEvidenceHit evidence(UUID sourceVersionId, String excerpt) {
        return new HybridEvidenceHit(new RuleEvidenceHit(
                chunkId,
                sourceVersionId,
                "RULES",
                "行动",
                excerpt,
                4,
                4,
                0.9), 0.9, 1, null, false);
    }
}
