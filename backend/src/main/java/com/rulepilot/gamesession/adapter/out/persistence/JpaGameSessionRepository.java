package com.rulepilot.gamesession.adapter.out.persistence;

import com.rulepilot.gamesession.application.GameSessionRepository;
import com.rulepilot.gamesession.domain.GameSession;
import com.rulepilot.gamesession.domain.GameSessionStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaGameSessionRepository implements GameSessionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public GameSession save(GameSession session) {
        entityManager.persist(new GameSessionEntity(session));
        entityManager.flush();
        return session;
    }

    @Override
    public Optional<GameSession> find(UUID sessionId) {
        return Optional.ofNullable(entityManager.find(GameSessionEntity.class, sessionId))
                .map(GameSessionEntity::toDomain);
    }

    @Override
    public void update(GameSession session) {
        GameSessionEntity entity = entityManager.find(GameSessionEntity.class, session.id());
        if (entity == null) {
            throw new IllegalArgumentException("game session does not exist");
        }
        entity.roundNumber = session.roundNumber();
        entity.phase = session.phase();
        entity.activePlayer = session.activePlayer();
        entity.status = session.status().name();
        entity.updatedAt = session.updatedAt();
        entityManager.flush();
    }
}

@Entity(name = "LiveGameSessionEntity")
@Table(name = "game_session")
class GameSessionEntity {

    @Id
    UUID id;

    @Column(name = "game_id", nullable = false)
    UUID gameId;

    @Column(name = "edition_id", nullable = false)
    UUID editionId;

    @Column(name = "document_version_id", nullable = false)
    UUID documentVersionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "game_session_expansion", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "expansion_id", nullable = false)
    Set<UUID> expansionIds = new HashSet<>();

    @Column(name = "player_count", nullable = false)
    int playerCount;

    @Column(name = "round_number", nullable = false)
    int roundNumber;

    @Column(nullable = false)
    String phase;

    @Column(name = "active_player")
    Integer activePlayer;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(nullable = false)
    String status;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected GameSessionEntity() {}

    GameSessionEntity(GameSession session) {
        id = session.id();
        gameId = session.gameId();
        editionId = session.editionId();
        documentVersionId = session.documentVersionId();
        expansionIds = new HashSet<>(session.expansionIds());
        playerCount = session.playerCount();
        roundNumber = session.roundNumber();
        phase = session.phase();
        activePlayer = session.activePlayer();
        createdBy = session.createdBy();
        status = session.status().name();
        createdAt = session.createdAt();
        updatedAt = session.updatedAt();
    }

    GameSession toDomain() {
        return new GameSession(
                id, gameId, editionId, documentVersionId, expansionIds, playerCount, roundNumber,
                phase, activePlayer, createdBy, GameSessionStatus.valueOf(status), createdAt, updatedAt);
    }
}
