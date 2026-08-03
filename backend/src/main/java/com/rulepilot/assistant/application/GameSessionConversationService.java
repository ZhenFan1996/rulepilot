package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
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
    public Optional<PriorTurnReference> priorTurnReference(UUID sessionId, String username, UUID documentVersionId) {
        List<GameSessionConversationTurn> history = turns.findRecent(sessionId, username, 1);
        if (history.isEmpty()) return Optional.empty();
        GameSessionConversationTurn turn = history.getLast();
        StructuredRuleAnswer answer = turn.answer();
        if (!documentVersionId.equals(answer.documentVersionId()) || !answer.status().publishesConclusion()) {
            return Optional.empty();
        }
        return Optional.of(new PriorTurnReference(
                documentVersionId,
                turn.question(),
                bounded(answer.shortVerdict(), 800),
                answer.citations().stream()
                        .filter(citation -> documentVersionId.equals(citation.documentVersionId()))
                        .limit(8)
                        .map(citation -> new PriorCitationReference(
                                citation.chunkId(), citation.documentVersionId(), citation.pageFrom(), citation.pageTo()))
                        .toList()));
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "No prior grounded verdict";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
