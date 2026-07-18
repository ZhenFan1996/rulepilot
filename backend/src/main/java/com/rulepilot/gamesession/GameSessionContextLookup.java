package com.rulepilot.gamesession;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface GameSessionContextLookup {

    Optional<SessionContext> findOwned(UUID sessionId, String username);

    record SessionContext(
            UUID sessionId,
            UUID editionId,
            UUID documentVersionId,
            Set<UUID> expansionIds,
            int playerCount,
            int roundNumber,
            String phase,
            Integer activePlayer,
            String status) {
        public SessionContext {
            expansionIds = Set.copyOf(expansionIds);
        }
    }
}
