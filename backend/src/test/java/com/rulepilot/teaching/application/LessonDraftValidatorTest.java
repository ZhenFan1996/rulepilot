package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftValidatorTest {

    @Test
    void keepsAVisualStepBoundToItsAttachedAndCitedRulebookPage() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId, UUID.randomUUID(), "SETUP", "Setup", "Place the board in the center.", 3, 3);
        SectionRequest request = request(chunkId);
        SectionDraft draft = new SectionDraft(
                "完成开局",
                VisualKind.TABLE_LAYOUT,
                "在图中找到主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "找到主棋盘",
                        TeachingMove.VISUAL,
                        "在图中找到主棋盘。",
                        List.of(chunkId),
                        new VisualFocusDraft(3, "主棋盘", 150, 180, 450, 400))));
        Map<UUID, RuleEvidence> allowedEvidence = Map.of(chunkId, evidence);

        LessonDraftValidator.validateDraft(draft, request);
        LessonDraftValidator.validateVisualBlockEvidence(draft, request, allowedEvidence);
        LessonStep step = LessonDraftValidator.validatedStep(1, draft.steps().getFirst(), allowedEvidence);

        assertThat(LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence)).containsExactly(chunkId);
        assertThat(step.sourcePages()).containsExactly(3);
        assertThat(step.visualFocus()).isEqualTo(new VisualFocus(3, "主棋盘", 150, 180, 450, 400));
    }

    @Test
    void clampsAValidFocusButRejectsAnAlmostCompletePage() {
        assertThat(LessonDraftValidator.validatedFocus(new VisualFocusDraft(3, "主棋盘", -30, 990, 2_000, 100)))
                .isEqualTo(new VisualFocus(3, "主棋盘", 0, 980, 1_000, 20));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatedFocus(
                        new VisualFocusDraft(3, "整页", 0, 0, 1_000, 1_000)))
                .withMessageContaining("tight focus region");
    }

    @Test
    void requiresEveryCitedPlayerCountValueInsteadOfOnlyTheRequestedGameSize() {
        UUID chunkId = UUID.randomUUID();
        Map<UUID, RuleEvidence> evidence = Map.of(
                chunkId,
                new RuleEvidence(
                        chunkId,
                        UUID.randomUUID(),
                        "END",
                        "End of Game",
                        "The first player to collect the listed treasures triggers the end: 2 Players: 7, 3 Players: 6, 4 Players: 5.",
                        12,
                        12));
        SectionDraft incomplete = draft(chunkId, "在 4 人游戏中，先拿到 5 个宝藏会触发结束。");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatePlayerCountConditionalValues(incomplete, evidence))
                .withMessageContaining("every listed player-count/value condition")
                .withMessageContaining("2 players: 7; 3 players: 6; 4 players: 5");

        SectionDraft complete = draft(chunkId, "达到对应宝藏数才触发结束：2、3、4 人分别为 7、6、5 个。");
        LessonDraftValidator.validatePlayerCountConditionalValues(complete, evidence);
    }

    @Test
    void ignoresOrdinarySentencesThatMentionPlayersAndOtherNumbersButAreNotAConditionalValueTable() {
        UUID chunkId = UUID.randomUUID();
        Map<UUID, RuleEvidence> evidence = Map.of(
                chunkId,
                new RuleEvidence(
                        chunkId,
                        UUID.randomUUID(),
                        "ACTIONS",
                        "Recruit",
                        "If you have more than five gold or five heroes, you must discard down to five of each. "
                                + "All players then draw 3 cards.",
                        7,
                        7));

        LessonDraftValidator.validatePlayerCountConditionalValues(
                draft(chunkId, "回合结束时，金钱和英雄都不能超过 5。"), evidence);
    }

    @Test
    void doesNotMoveACitedConditionalTableIntoASectionThatDoesNotMentionItsPlayerCounts() {
        UUID chunkId = UUID.randomUUID();
        Map<UUID, RuleEvidence> evidence = Map.of(
                chunkId,
                new RuleEvidence(
                        chunkId,
                        UUID.randomUUID(),
                        "END",
                        "End of Game",
                        "The first player to collect the listed treasures triggers the end: 2 Players: 7, 3 Players: 6, 4 Players: 5.",
                        12,
                        12));

        LessonDraftValidator.validatePlayerCountConditionalValues(
                draft(chunkId, "收集宝藏越多越接近胜利。"), evidence);
    }

    @Test
    void requiresEveryCitedPlayerCountInAConditionalRange() {
        UUID chunkId = UUID.randomUUID();
        Map<UUID, RuleEvidence> evidence = Map.of(
                chunkId,
                new RuleEvidence(
                        chunkId,
                        UUID.randomUUID(),
                        "END",
                        "End of Game",
                        "At the end of the 7th round (if playing with 2 or 3 players) or the 10th round (if playing with 4 or 5 players) the game ends.",
                        10,
                        10));
        SectionDraft narrowed = draft(chunkId, "4人游戏在第10轮结束后结束。");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatePlayerCountConditionalScopes(narrowed, evidence))
                .withMessageContaining("every listed player count")
                .withMessageContaining("if playing with 2 or 3 players")
                .withMessageContaining("if playing with 4 or 5 players");

        LessonDraftValidator.validatePlayerCountConditionalScopes(
                draft(chunkId, "2人或3人游戏在第7轮结束，4人或5人游戏在第10轮结束。"), evidence);
    }

    @Test
    void requiresTheCitedSharedVictoryAtTheEndOfATieBreakChain() {
        UUID chunkId = UUID.randomUUID();
        Map<UUID, RuleEvidence> evidence = Map.of(
                chunkId,
                new RuleEvidence(
                        chunkId,
                        UUID.randomUUID(),
                        "SCORING",
                        "Tie",
                        "In case of a tie, compare completed missions, then Doubloons, then tokens on the Player board. Further ties result in a shared victory.",
                        10,
                        10));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validateSharedTieResolution(
                        draft(chunkId, "平局时比较任务数和达布隆数；仍平局则比较韧性。"), evidence))
                .withMessageContaining("shared victory")
                .withMessageContaining("printed comparison order");

        LessonDraftValidator.validateSharedTieResolution(
                draft(chunkId, "平局时依次比较任务数、达布隆数和玩家板令牌数；仍平局则共享胜利。"), evidence);
    }

    private SectionDraft draft(UUID chunkId, String text) {
        return new SectionDraft(
                "结束条件",
                VisualKind.REFERENCE_CARD,
                "按人数检查宝藏数。",
                List.of(chunkId),
                List.of(new StepDraft("检查结束", TeachingMove.FLOW, text, List.of(chunkId))));
    }

    private SectionRequest request(UUID chunkId) {
        return new SectionRequest(
                "setup",
                "完成开局",
                "让玩家完成开局布置",
                List.of("开局"),
                2,
                1,
                15,
                120,
                3,
                List.of(),
                List.of(new EvidenceInput(chunkId, "SETUP", "Setup", "Place the board in the center.", 3, 3)),
                List.of(new PageImageInput(3, "image/png", new byte[] {1}, 100, 100)));
    }
}
