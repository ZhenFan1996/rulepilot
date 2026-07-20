package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSessionConversationServiceTest {

    @Test
    void recordsOwnerScopedTurnsAndUsesLatestQuestionForReferenceResolution() {
        InMemoryTurns repository = new InMemoryTurns();
        GameSessionConversationService conversations = new GameSessionConversationService(repository);
        UUID sessionId = UUID.randomUUID();

        conversations.record(sessionId, "月球登陆要付多少？", answer(), "alice");
        conversations.record(sessionId, "那之后还能再做一次吗？", answer(), "alice");
        conversations.record(sessionId, "另一个人的问题", answer(), "bob");

        assertThat(conversations.history(sessionId, "alice"))
                .extracting(GameSessionConversationTurn::question)
                .containsExactly("月球登陆要付多少？", "那之后还能再做一次吗？");
        assertThat(conversations.previousQuestion(sessionId, "alice"))
                .contains("那之后还能再做一次吗？");
    }

    private StructuredRuleAnswer answer() {
        UUID versionId = UUID.randomUUID();
        return new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "按登陆该行星的正常费用支付。",
                "月球登陆的费用与所在行星相同。",
                List.of(new RuleCitation(
                        UUID.randomUUID(), versionId, "ACTIONS", "月球", "费用与登陆该行星相同。", 17, 17)),
                List.of(),
                AnswerConfidence.HIGH,
                false,
                null,
                null,
                null);
    }

    private static final class InMemoryTurns implements GameSessionConversationRepository {
        private final List<GameSessionConversationTurn> values = new ArrayList<>();

        @Override
        public void save(GameSessionConversationTurn turn) {
            values.add(turn);
        }

        @Override
        public List<GameSessionConversationTurn> findRecent(UUID sessionId, String username, int limit) {
            List<GameSessionConversationTurn> recent = values.stream()
                    .filter(turn -> turn.sessionId().equals(sessionId) && turn.createdBy().equals(username))
                    .sorted(Comparator.comparing(GameSessionConversationTurn::createdAt).reversed())
                    .limit(limit)
                    .sorted(Comparator.comparing(GameSessionConversationTurn::createdAt))
                    .toList();
            return recent;
        }
    }
}
