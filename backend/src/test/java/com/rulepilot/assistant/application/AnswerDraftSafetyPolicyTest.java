package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerDraftSafetyPolicyTest {

    @Test
    void removesInternalReferencesAndReplacesOneResolvedGlyph() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft draft = new ModelDraft(
                "支付 🟢 [E1]",
                "参见 chunk 48a31827-0ebf-42f2-8b9f-8a33c842e15e（，）后执行。",
                List.of(chunkId),
                List.of(),
                "HIGH");

        ModelDraft normalized = AnswerDraftSafetyPolicy.normalizeSingleMappedVisualGlyph(draft, List.of("Energy token"));
        normalized = AnswerDraftSafetyPolicy.normalizeDanglingPunctuation(normalized);
        normalized = AnswerDraftSafetyPolicy.normalizeInternalEvidenceReferences(normalized);

        assertThat(normalized.shortVerdict()).isEqualTo("支付 Energy token");
        assertThat(normalized.explanation()).doesNotContain("chunk", "48a31827", "E1", "（，）");
        assertThat(AnswerDraftSafetyPolicy.containsInternalEvidenceReference(normalized)).isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsVisualGlyph(normalized)).isFalse();
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
    void keepsTitleLabelsAndActorSafetyTiedToEvidenceAndText() {
        UUID chunkId = UUID.randomUUID();
        ModelRequest uncitedTitle = request(
                "What happens at the end?", new EvidenceInput(chunkId, "RULES", "Ending", "Finish the round.", 8, 8));
        ModelRequest citedTitle = request(
                "What happens at the end?",
                new EvidenceInput(chunkId, "RULES", "Ending", "Follow the Final Cleanup sequence.", 8, 8));
        ModelDraft titleDraft = new ModelDraft(
                "Final Cleanup", "then finish the round.", List.of(chunkId), List.of(), "HIGH");
        ModelDraft inactiveActor = new ModelDraft(
                "继续本轮。", "手牌为空后，你领出下一墩。", List.of(chunkId), List.of(), "HIGH");

        assertThat(AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(uncitedTitle, titleDraft)).isTrue();
        assertThat(AnswerDraftSafetyPolicy.containsUncitedEnglishTitleLabel(citedTitle, titleDraft)).isFalse();
        assertThat(AnswerDraftSafetyPolicy.containsInactiveActorContinuation(inactiveActor)).isTrue();
        assertThat(AnswerDraftSafetyPolicy.hasEvidencedSuccessorRule(request(
                        "Who leads next?",
                        new EvidenceInput(
                                chunkId,
                                "RULES",
                                "Next trick",
                                "If a player is out of cards, the next player to the left starts the next trick.",
                                9,
                                9))))
                .isTrue();
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

    private ModelRequest request(String question, EvidenceInput evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, 0, null, null, PlayerLocale.ZH_CN),
                List.of(evidence));
    }
}
