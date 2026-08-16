package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnswerDraftComposerTest {

    @Test
    void retainsAReadyCitedDraftAfterTheBoundedModelWorkflow() {
        UUID chunkId = UUID.randomUUID();
        ModelDraft expected = new ModelDraft(
                "行动完成后获得1分。",
                "规则说明：完成行动后获得1分。",
                List.of(chunkId),
                List.of(),
                "HIGH");
        RuleAnswerModel model = request -> expected;
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, request(chunkId));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(expected);
        assertThat(result.failureStatus()).isNull();
        assertThat(result.modelRepairs()).isZero();
    }

    @Test
    void repairsAnUnsafeSourceWideClaimThroughTheModelAndPublishesTheRepairVerbatim() {
        UUID chunkId = UUID.randomUUID();
        AtomicInteger revisions = new AtomicInteger();
        ModelDraft repaired = new ModelDraft(
                "达到30分即可获胜。",
                "关于开局，当前提供的摘录不足以确认是否有保证获胜的策略。你希望我继续核对哪个具体阵营或阶段？",
                List.of(chunkId),
                List.of(),
                "HIGH");
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "达到30分即可获胜。规则书未提及保证获胜的最佳开局。",
                        "当前提供的规则摘录无法确认最佳开局。",
                        List.of(chunkId),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return repaired;
            }
        };
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, adviceRequest(chunkId));

        assertThat(result.ready()).isTrue();
        assertThat(result.draft()).isEqualTo(repaired);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.modelRepairs()).isEqualTo(1);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void rejectsAnUnsafeSourceWideClaimWhenTheSingleModelRepairDoesNotFixIt() {
        UUID chunkId = UUID.randomUUID();
        AtomicInteger revisions = new AtomicInteger();
        ModelDraft unsafe = new ModelDraft(
                "达到30分即可获胜。规则书没有提供保证获胜的开局。",
                "达到30分即可获胜。当前摘录无法确认开局建议。你想继续查哪个阵营？",
                List.of(chunkId),
                List.of(),
                "HIGH");
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return unsafe;
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        AnswerDraftComposer composer = new AnswerDraftComposer(new AnswerModelGateway(
                model, new PermissiveRateLimiter(), new ImmediateAuditedAgentInvocations()));

        AnswerDraftComposer.Result result = composer.compose(
                UUID.randomUUID(), "player", null, adviceRequest(chunkId));

        assertThat(result.ready()).isFalse();
        assertThat(result.failureStatus()).isEqualTo(com.rulepilot.assistant.domain.AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(result.draft()).isNull();
        assertThat(revisions).hasValue(1);
    }

    private ModelRequest request(UUID chunkId) {
        return new ModelRequest(
                "完成行动后会发生什么？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        chunkId,
                        "ACTIONS",
                        "完成行动",
                        "完成行动后获得1分。",
                        5,
                        5)));
    }

    private ModelRequest adviceRequest(UUID chunkId) {
        return new ModelRequest(
                "怎么赢？有没有保证获胜的开局？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(chunkId, "RULE", "获胜", "达到30分即可获胜。", 5, 5)),
                Set.of(EvidenceNeed.DIRECT_RULE, EvidenceNeed.ADVICE),
                AnswerAid.NONE,
                List.of(
                        new PlannedSubquestion("怎么赢？", Set.of(EvidenceNeed.DIRECT_RULE)),
                        new PlannedSubquestion("有没有保证获胜的开局？", Set.of(EvidenceNeed.ADVICE))));
    }

    private static final class PermissiveRateLimiter implements RuleAnswerRateLimiter {

        @Override
        public void checkUser(String username) {}

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            return () -> {};
        }
    }
}
