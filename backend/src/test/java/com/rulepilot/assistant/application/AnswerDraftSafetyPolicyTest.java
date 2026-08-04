package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftSafetyPolicyTest {

    @Test
    void removesInternalReferencesAndReplacesOneResolvedGlyph() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft draft = new ModelDraft(
                true,
                null,
                "支付 🟢 [E1]",
                "参见 chunk 48a31827-0ebf-42f2-8b9f-8a33c842e15e（，）后执行。",
                List.of(chunkId),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION");

        ModelDraft normalized = AnswerDraftSafetyPolicy.normalizeSingleMappedVisualGlyph(draft, List.of("Energy token"));
        normalized = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(normalized);
        normalized = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(normalized);

        assertThat(normalized.shortVerdict()).isEqualTo("支付 Energy token");
        assertThat(normalized.explanation()).doesNotContain("chunk", "48a31827", "E1", "（，）");
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(normalized)).isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsVisualGlyph(normalized)).isFalse();
        assertThat(normalized.answerBasis()).isEqualTo("GROUNDED_APPLICATION");
    }

    @Test
    void detectsOnlyUnsupportedPeripheralRepeatabilityClaims() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft draft = new ModelDraft(
                "可以领取奖励。", "每个奖励每回合最多领取一次。", List.of(chunkId), List.of(), "HIGH");
        ModelRequest ordinaryQuestion = request(
                "How do I gain this reward?", new EvidenceInput(chunkId, "RULES", "Reward", "Gain the reward.", 4, 4));
        ModelRequest repeatabilityQuestion = request(
                "How many times can I gain this reward?",
                new EvidenceInput(chunkId, "RULES", "Reward", "Gain the reward.", 4, 4));
        ModelRequest evidencedClaim = request(
                "How do I gain this reward?",
                new EvidenceInput(chunkId, "RULES", "Reward", "You may gain this reward only once per turn.", 4, 4));

        assertThat(AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(ordinaryQuestion, draft))
                .isTrue();
        assertThat(AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(repeatabilityQuestion, draft))
                .isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsUnaskedUnsupportedRepeatabilityClaim(evidencedClaim, draft))
                .isFalse();
    }

    @Test
    void keepsTitleLabelsTiedToCitedEvidence() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest uncitedTitle = request(
                "What happens at the end?", new EvidenceInput(chunkId, "RULES", "Ending", "Finish the round.", 8, 8));
        ModelRequest citedTitle = request(
                "What happens at the end?",
                new EvidenceInput(chunkId, "RULES", "Ending", "Follow the Final Cleanup sequence.", 8, 8));
        ModelDraft titleDraft = new ModelDraft(
                "Final Cleanup", "then finish the round.", List.of(chunkId), List.of(), "HIGH");

        assertThat(AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(uncitedTitle, titleDraft)).isTrue();
        assertThat(AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(citedTitle, titleDraft)).isFalse();
    }

    @Test
    void distinguishesAResourceIconFromANegatedCardRequirement() {
        ModelDraft conflated = new ModelDraft(
                "需要资源。", "该图标表示至少两张手牌才能发动。", List.of(UUID.randomUUID()), List.of(), "HIGH");
        ModelDraft negated = new ModelDraft(
                "不需要手牌。", "该图标不需要两张手牌。", List.of(UUID.randomUUID()), List.of(), "HIGH");

        assertThat(AnswerDraftSafetyPolicy.containsResourceCardConflation(conflated)).isTrue();
        assertThat(AnswerDraftSafetyPolicy.containsResourceCardConflation(negated)).isFalse();
    }

    @Test
    void detectsAProposedDefinitionAfterTheDraftAdmitsThatDefinitionIsMissing() {
        ModelDraft speculative = new ModelDraft(
                "现有规则没有定义该状态。",
                "规则未明确说明达成条件；玩家可自行确认是否需要相邻。",
                List.of(UUID.randomUUID()),
                List.of(),
                "HIGH");
        ModelDraft bounded = new ModelDraft(
                "现有规则没有定义该状态。",
                "可以确认该状态下得3分，但当前证据未说明如何达成。",
                List.of(UUID.randomUUID()),
                List.of(),
                "HIGH");

        assertThat(AnswerDraftSafetyPolicy.containsSpeculativeUndefinedTermDefinition(speculative))
                .isTrue();
        assertThat(AnswerDraftSafetyPolicy.containsSpeculativeUndefinedTermDefinition(bounded))
                .isFalse();
    }

    @Test
    void removesUnsupportedAbsenceClaimsFromDirectSourceExplanations() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest sourceQuestion = new ModelRequest(
                "Show me the source rule.",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.SOURCE, PlayerLocale.EN),
                List.of(new EvidenceInput(
                        chunkId,
                        "RULES",
                        "Bird cards",
                        "Bird cards are wild: they count as any other suit.",
                        6,
                        6)));
        ModelDraft draft = new ModelDraft(
                "Yes, a bird card can count as a fox card.",
                "Bird cards can count as fox cards. The word always makes this available without any listed "
                        + "restriction or condition. There is no additional condition stated in this excerpt. "
                        + "The clause does not specify an exact moment. The permission applies without imposing any "
                        + "additional condition or limitation. It is not limited to a particular phase. The rule "
                        + "does not mention a saving mechanism, nor does it establish another timing boundary. This "
                        + "treatment is not limited by any specified condition. The rule does not specify an exact "
                        + "instant or cleanup step. It does not impose any requirement or timing restriction. The "
                        + "clause does not define the larger cycle. The only stated timing is during this turn. It "
                        + "does not attach a condition or limit.",
                List.of(chunkId),
                List.of(),
                "HIGH");

        ModelDraft normalized = AnswerDraftSafetyPolicy.normalizeSourceAbsenceClaims(sourceQuestion, draft);

        assertThat(normalized.shortVerdict()).isEqualTo(draft.shortVerdict());
        assertThat(normalized.explanation()).isEqualTo("Bird cards can count as fox cards.");
    }

    @Test
    void removesUnsupportedAbsenceClaimsFromPermissionExplanations() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest permissionQuestion = request(
                "Can I use this card?",
                new EvidenceInput(chunkId, "RULES", "Permission", "You may use this card.", 4, 4));
        ModelDraft draft = new ModelDraft(
                "Yes, you may use this card.",
                "The rule grants permission. It applies without the need for any special condition or timing. "
                        + "Without a specified exception in the evidence, it always applies. The only condition is "
                        + "that you passed.",
                List.of(chunkId), List.of(), "HIGH");

        ModelDraft normalized = AnswerDraftSafetyPolicy.normalizeSourceAbsenceClaims(permissionQuestion, draft);

        assertThat(normalized.explanation()).isEqualTo("The rule grants permission.");
    }

    private ModelRequest request(String question, EvidenceInput evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(evidence));
    }
}
