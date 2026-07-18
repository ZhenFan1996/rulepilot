package com.rulepilot.gamesession.application;

import com.rulepilot.gamesession.GameSessionContextLookup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class GameSessionContextLookupService implements GameSessionContextLookup {

    private final GameSessionService sessions;

    GameSessionContextLookupService(GameSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public java.util.Optional<SessionContext> findOwned(java.util.UUID sessionId, String username) {
        try {
            var session = sessions.get(sessionId, username);
            return java.util.Optional.of(new SessionContext(session.id(), session.documentVersionId()));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}
