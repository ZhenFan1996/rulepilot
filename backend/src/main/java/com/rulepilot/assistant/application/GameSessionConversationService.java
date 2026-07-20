package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class GameSessionConversationService {

    private static final int HISTORY_LIMIT = 20;

    private final GameSessionConversationRepository turns;
    private final Clock clock = Clock.systemUTC();

    public GameSessionConversationService(GameSessionConversationRepository turns) {
        this.turns = turns;
    }

    @Transactional
    public GameSessionConversationTurn record(
            UUID sessionId, String question, StructuredRuleAnswer answer, String username) {
        GameSessionConversationTurn turn = GameSessionConversationTurn.create(
                sessionId, question, answer, username, Instant.now(clock));
        turns.save(turn);
        return turn;
    }

    @Transactional(readOnly = true)
    public List<GameSessionConversationTurn> history(UUID sessionId, String username) {
        return turns.findRecent(sessionId, username, HISTORY_LIMIT);
    }

    @Transactional(readOnly = true)
    public Optional<String> previousQuestion(UUID sessionId, String username) {
        List<GameSessionConversationTurn> history = turns.findRecent(sessionId, username, 1);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast().question());
    }
}
