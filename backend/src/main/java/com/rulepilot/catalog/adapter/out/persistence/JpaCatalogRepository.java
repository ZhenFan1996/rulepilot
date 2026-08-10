package com.rulepilot.catalog.adapter.out.persistence;

import com.rulepilot.catalog.PublicGameCoverLookup.Cover;
import com.rulepilot.catalog.application.CatalogRepository;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaCatalogRepository implements CatalogRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Game save(Game game) {
        entityManager.persist(new GameEntity(game));
        entityManager.flush();
        return game;
    }

    @Override
    public GameEdition save(GameEdition edition) {
        entityManager.persist(new GameEditionEntity(edition));
        entityManager.flush();
        return edition;
    }

    @Override
    public Expansion save(Expansion expansion) {
        entityManager.persist(new ExpansionEntity(expansion));
        expansion.compatibleEditionIds().forEach(editionId -> entityManager.persist(
                new EditionExpansionEntity(new EditionExpansionId(editionId, expansion.id()))));
        entityManager.flush();
        return expansion;
    }

    @Override
    public BggGameMetadata save(BggGameMetadata metadata) {
        entityManager.persist(new BggGameMetadataEntity(metadata));
        entityManager.flush();
        return metadata;
    }

    @Override
    public Optional<Game> findGame(UUID gameId) {
        GameEntity entity = entityManager.find(GameEntity.class, gameId);
        return Optional.ofNullable(entity).map(GameEntity::toDomain);
    }

    @Override
    public Optional<Game> findGameByName(String name) {
        return entityManager
                .createQuery(
                        "select game from CatalogGameEntity game where lower(game.name) = lower(:name)",
                        GameEntity.class)
                .setParameter("name", name)
                .getResultStream()
                .findFirst()
                .map(GameEntity::toDomain);
    }

    @Override
    public Optional<GameEdition> findEdition(UUID editionId) {
        GameEditionEntity entity = entityManager.find(GameEditionEntity.class, editionId);
        return Optional.ofNullable(entity).map(GameEditionEntity::toDomain);
    }

    @Override
    public Optional<GameEdition> findEdition(UUID gameId, String name, String language) {
        return entityManager
                .createQuery(
                        """
                        select edition from CatalogGameEditionEntity edition
                        where edition.gameId = :gameId
                          and lower(edition.name) = lower(:name)
                          and lower(edition.language) = lower(:language)
                        """,
                        GameEditionEntity.class)
                .setParameter("gameId", gameId)
                .setParameter("name", name)
                .setParameter("language", language)
                .getResultStream()
                .findFirst()
                .map(GameEditionEntity::toDomain);
    }

    @Override
    public Optional<Game> findGameByBggId(int bggId) {
        return entityManager.createQuery(
                        "select game from CatalogGameEntity game, CatalogBggGameMetadataEntity metadata "
                                + "where metadata.gameId = game.id and metadata.bggId = :bggId",
                        GameEntity.class)
                .setParameter("bggId", bggId)
                .getResultStream()
                .findFirst()
                .map(GameEntity::toDomain);
    }

    @Override
    public Optional<BggGameMetadata> findBggMetadata(UUID gameId) {
        return Optional.ofNullable(entityManager.find(BggGameMetadataEntity.class, gameId))
                .map(BggGameMetadataEntity::toDomain);
    }

    @Override
    public List<Game> findGames() {
        return entityManager.createQuery("select game from CatalogGameEntity game order by game.name", GameEntity.class)
                .getResultStream()
                .map(GameEntity::toDomain)
                .toList();
    }

    @Override
    public List<GameEdition> findEditions(UUID gameId) {
        return entityManager.createQuery(
                        "select edition from CatalogGameEditionEntity edition where edition.gameId = :gameId order by edition.name",
                        GameEditionEntity.class)
                .setParameter("gameId", gameId)
                .getResultStream()
                .map(GameEditionEntity::toDomain)
                .toList();
    }

    @Override
    public List<Expansion> findExpansions(UUID gameId) {
        List<ExpansionEntity> expansions = entityManager.createQuery(
                        "select expansion from CatalogExpansionEntity expansion where expansion.gameId = :gameId order by expansion.name",
                        ExpansionEntity.class)
                .setParameter("gameId", gameId)
                .getResultList();
        return expansions.stream()
                .map(entity -> entity.toDomain(findCompatibleEditionIds(entity.id)))
                .toList();
    }

    @Override
    public Map<UUID, Cover> findCoversByEditions(Collection<UUID> editionIds) {
        if (editionIds == null || editionIds.isEmpty()) return Map.of();
        List<GameEditionEntity> editions = entityManager
                .createQuery(
                        "select e from CatalogGameEditionEntity e where e.id in :editionIds", GameEditionEntity.class)
                .setParameter("editionIds", editionIds)
                .getResultList();
        if (editions.isEmpty()) return Map.of();
        Map<UUID, GameEntity> games = entityManager
                .createQuery("select g from CatalogGameEntity g where g.id in :gameIds", GameEntity.class)
                .setParameter("gameIds", editions.stream().map(edition -> edition.gameId).toList())
                .getResultList()
                .stream()
                .collect(java.util.stream.Collectors.toMap(game -> game.id, game -> game));
        Map<UUID, BggGameMetadataEntity> metadata = entityManager
                .createQuery(
                        "select m from CatalogBggGameMetadataEntity m where m.gameId in :gameIds",
                        BggGameMetadataEntity.class)
                .setParameter("gameIds", games.keySet())
                .getResultList()
                .stream()
                .collect(java.util.stream.Collectors.toMap(value -> value.gameId, value -> value));
        Map<UUID, Cover> result = new LinkedHashMap<>();
        editions.forEach(edition -> {
            GameEntity game = games.get(edition.gameId);
            BggGameMetadataEntity bgg = metadata.get(edition.gameId);
            if (game != null && bgg != null && !bgg.thumbnailUrl.isBlank()) {
                result.put(
                        edition.id,
                        new Cover(game.name, bgg.bggId, bgg.thumbnailUrl, "https://boardgamegeek.com/boardgame/" + bgg.bggId));
            }
        });
        return Map.copyOf(result);
    }

    private Set<UUID> findCompatibleEditionIds(UUID expansionId) {
        return new LinkedHashSet<>(entityManager.createQuery(
                        "select compatibility.id.editionId from CatalogEditionExpansionEntity compatibility "
                                + "where compatibility.id.expansionId = :expansionId",
                        UUID.class)
                .setParameter("expansionId", expansionId)
                .getResultList());
    }
}

@Entity(name = "CatalogBggGameMetadataEntity")
@Table(name = "bgg_game_metadata")
class BggGameMetadataEntity {

    @Id
    @Column(name = "game_id")
    UUID gameId;

    @Column(name = "bgg_id", nullable = false, unique = true)
    int bggId;

    @Column(nullable = false, columnDefinition = "text")
    String description;

    @Column(name = "thumbnail_url", nullable = false, columnDefinition = "text")
    String thumbnailUrl;

    @Column(name = "min_players")
    Integer minPlayers;

    @Column(name = "max_players")
    Integer maxPlayers;

    @Column(name = "playing_time_minutes")
    Integer playingTimeMinutes;

    @Column(name = "minimum_age")
    Integer minimumAge;

    @Column(name = "imported_at", nullable = false)
    Instant importedAt;

    protected BggGameMetadataEntity() {}

    BggGameMetadataEntity(BggGameMetadata metadata) {
        gameId = metadata.gameId();
        bggId = metadata.bggId();
        description = metadata.description();
        thumbnailUrl = metadata.thumbnailUrl();
        minPlayers = metadata.minPlayers();
        maxPlayers = metadata.maxPlayers();
        playingTimeMinutes = metadata.playingTimeMinutes();
        minimumAge = metadata.minimumAge();
        importedAt = metadata.importedAt();
    }

    BggGameMetadata toDomain() {
        return new BggGameMetadata(
                gameId,
                bggId,
                description,
                thumbnailUrl,
                minPlayers,
                maxPlayers,
                playingTimeMinutes,
                minimumAge,
                importedAt);
    }
}

@Entity(name = "CatalogGameEntity")
@Table(name = "game")
class GameEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 120)
    String name;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected GameEntity() {}

    GameEntity(Game game) {
        id = game.id();
        name = game.name();
        createdAt = game.createdAt();
    }

    Game toDomain() {
        return new Game(id, name, createdAt);
    }
}

@Entity(name = "CatalogGameEditionEntity")
@Table(name = "game_edition")
class GameEditionEntity {

    @Id
    UUID id;

    @Column(name = "game_id", nullable = false)
    UUID gameId;

    @Column(nullable = false, length = 120)
    String name;

    @Column(nullable = false, length = 20)
    String language;

    @Column(name = "publication_year")
    Integer publicationYear;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected GameEditionEntity() {}

    GameEditionEntity(GameEdition edition) {
        id = edition.id();
        gameId = edition.gameId();
        name = edition.name();
        language = edition.language();
        publicationYear = edition.publicationYear();
        createdAt = edition.createdAt();
    }

    GameEdition toDomain() {
        return new GameEdition(id, gameId, name, language, publicationYear, createdAt);
    }
}

@Entity(name = "CatalogExpansionEntity")
@Table(name = "expansion")
class ExpansionEntity {

    @Id
    UUID id;

    @Column(name = "game_id", nullable = false)
    UUID gameId;

    @Column(nullable = false, length = 120)
    String name;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected ExpansionEntity() {}

    ExpansionEntity(Expansion expansion) {
        id = expansion.id();
        gameId = expansion.gameId();
        name = expansion.name();
        createdAt = expansion.createdAt();
    }

    Expansion toDomain(Set<UUID> editionIds) {
        return new Expansion(id, gameId, name, editionIds, createdAt);
    }
}

@Entity(name = "CatalogEditionExpansionEntity")
@Table(name = "edition_expansion")
class EditionExpansionEntity {

    @EmbeddedId
    EditionExpansionId id;

    protected EditionExpansionEntity() {}

    EditionExpansionEntity(EditionExpansionId id) {
        this.id = id;
    }
}

@Embeddable
class EditionExpansionId implements Serializable {

    @Column(name = "edition_id", nullable = false)
    UUID editionId;

    @Column(name = "expansion_id", nullable = false)
    UUID expansionId;

    protected EditionExpansionId() {}

    EditionExpansionId(UUID editionId, UUID expansionId) {
        this.editionId = editionId;
        this.expansionId = expansionId;
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof EditionExpansionId other
                && editionId.equals(other.editionId)
                && expansionId.equals(other.expansionId);
    }

    @Override
    public int hashCode() {
        return 31 * editionId.hashCode() + expansionId.hashCode();
    }
}
