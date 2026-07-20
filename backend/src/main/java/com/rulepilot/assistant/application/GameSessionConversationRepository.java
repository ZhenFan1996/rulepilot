package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionConversationRepository {

    void save(GameSessionConversationTurn turn);

    List<GameSessionConversationTurn> findRecent(UUID sessionId, String username, int limit);

    Optional<GameSessionConversationTurn> findOwned(UUID turnId, UUID sessionId, String username);
}
