package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class BggCatalogImportService {

    private final BoardGameGeekCatalog bgg;
    private final CatalogRepository repository;
    private final Clock clock;

    @Autowired
    public BggCatalogImportService(BoardGameGeekCatalog bgg, CatalogRepository repository) {
        this(bgg, repository, Clock.systemUTC());
    }

    BggCatalogImportService(BoardGameGeekCatalog bgg, CatalogRepository repository, Clock clock) {
        this.bgg = bgg;
        this.repository = repository;
        this.clock = clock;
    }

    public boolean configured() {
        return bgg.configured();
    }

    public List<SearchResult> search(String query) {
        String checked = query == null ? "" : query.trim();
        if (checked.length() < 2 || checked.length() > 120) {
            throw new IllegalArgumentException("BGG search query must contain 2 to 120 characters");
        }
        return bgg.search(checked).stream().limit(20).toList();
    }

    @Transactional
    public ImportedGame importGame(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        return repository.findGameByBggId(bggId)
                .map(game -> existing(game, repository.findBggMetadata(game.id()).orElseThrow()))
                .orElseGet(() -> importNew(bgg.game(bggId)));
    }

    private ImportedGame importNew(GameDetails source) {
        Instant now = Instant.now(clock);
        Game game = repository.save(Game.create(source.name(), now));
        GameEdition edition = repository.save(GameEdition.create(
                game.id(), "BGG 基础版", "und", source.publicationYear(), now));
        BggGameMetadata metadata = repository.save(new BggGameMetadata(
                game.id(),
                source.bggId(),
                source.description(),
                source.thumbnailUrl(),
                source.minPlayers(),
                source.maxPlayers(),
                source.playingTimeMinutes(),
                source.minimumAge(),
                now));
        return new ImportedGame(game, edition, metadata, false);
    }

    private ImportedGame existing(Game game, BggGameMetadata metadata) {
        GameEdition edition = repository.findEditions(game.id()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("imported BGG game has no edition"));
        return new ImportedGame(game, edition, metadata, true);
    }

    public record ImportedGame(Game game, GameEdition edition, BggGameMetadata metadata, boolean alreadyImported) {}
}
