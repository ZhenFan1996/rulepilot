package com.rulepilot.gamesession.application;

import com.rulepilot.gamesession.domain.GameSession;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionContextStore {

    void save(GameSession session);

    Optional<GameSession> find(UUID sessionId);
}
