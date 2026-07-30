package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
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
    }

    private ModelRequest request(UUID chunkId) {
        return new ModelRequest(
                "完成行动后会发生什么？",
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, null, PlayerLocale.ZH_CN),
                List.of(new EvidenceInput(
                        chunkId,
                        "ACTIONS",
                        "完成行动",
                        "完成行动后获得1分。",
                        5,
                        5)));
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
