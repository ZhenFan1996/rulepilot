package com.rulepilot.catalog.application;

import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class GameCatalogService {

    private final CatalogRepository repository;
    private final Clock clock;

    public GameCatalogService(CatalogRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public Game createGame(String name) {
        return repository.save(Game.create(name, Instant.now(clock)));
    }

    @Transactional
    public GameEdition createEdition(UUID gameId, String name, String language, Integer publicationYear) {
        requireGame(gameId);
        return repository.save(GameEdition.create(gameId, name, language, publicationYear, Instant.now(clock)));
    }

    @Transactional
    public Expansion createExpansion(UUID gameId, String name, Set<UUID> compatibleEditionIds) {
        requireGame(gameId);
        Set<UUID> editionIds = new HashSet<>();
        repository.findEditions(gameId).forEach(edition -> editionIds.add(edition.id()));
        if (!editionIds.containsAll(compatibleEditionIds)) {
            throw new IllegalArgumentException("every compatible edition must belong to the game");
        }
        return repository.save(Expansion.create(
                gameId, name, compatibleEditionIds, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<GameCatalogView> listCatalog() {
        return repository.findGames().stream()
                .map(game -> new GameCatalogView(
                        game,
                        repository.findEditions(game.id()),
                        repository.findExpansions(game.id()),
                        repository.findBggMetadata(game.id())))
                .toList();
    }

    private Game requireGame(UUID gameId) {
        return repository.findGame(gameId)
                .orElseThrow(() -> new IllegalArgumentException("game does not exist"));
    }
}
