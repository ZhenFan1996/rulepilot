package com.rulepilot.gamesession;

import java.util.Optional;
import java.util.UUID;

public interface GameSessionContextLookup {

    Optional<SessionContext> findOwned(UUID sessionId, String username);

    record SessionContext(UUID sessionId, UUID documentVersionId) {}
}
