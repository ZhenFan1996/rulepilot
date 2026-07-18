package com.rulepilot.gamesession.application;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.gamesession.domain.GameSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class GameSessionService {

    private final CatalogEditionLookup catalog;
    private final DocumentVersionScopeLookup documents;
    private final GameSessionRepository sessions;
    private final Clock clock = Clock.systemUTC();

    public GameSessionService(
            CatalogEditionLookup catalog, DocumentVersionScopeLookup documents, GameSessionRepository sessions) {
        this.catalog = catalog;
        this.documents = documents;
        this.sessions = sessions;
    }

    @Transactional
    public GameSession start(
            UUID editionId,
            UUID documentVersionId,
            Set<UUID> expansionIds,
            int playerCount,
            String phase,
            Integer activePlayer,
            String username) {
        var edition = catalog.findEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("game edition does not exist"));
        var version = documents.findVersion(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
        if (!version.editionId().equals(editionId)) {
            throw new IllegalArgumentException("document version does not belong to the selected edition");
        }
        if (!"READY".equals(version.processingStatus())) {
            throw new IllegalArgumentException("document version is not ready for a live session");
        }
        Set<UUID> selectedExpansions = expansionIds == null ? Set.of() : Set.copyOf(expansionIds);
        if (!edition.compatibleExpansionIds().containsAll(selectedExpansions)) {
            throw new IllegalArgumentException("an expansion is incompatible with the selected edition");
        }
        return sessions.save(GameSession.start(
                edition.gameId(), editionId, documentVersionId, selectedExpansions, playerCount,
                phase, activePlayer, username, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public GameSession get(UUID sessionId, String username) {
        return owned(sessionId, username);
    }

    @Transactional
    public GameSession updateTurn(
            UUID sessionId, int roundNumber, String phase, Integer activePlayer, String username) {
        GameSession updated = owned(sessionId, username)
                .updateTurn(roundNumber, phase, activePlayer, Instant.now(clock));
        sessions.update(updated);
        return updated;
    }

    private GameSession owned(UUID sessionId, String username) {
        GameSession session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("game session does not exist"));
        if (!session.createdBy().equals(username)) {
            throw new IllegalArgumentException("game session does not exist");
        }
        return session;
    }
}
