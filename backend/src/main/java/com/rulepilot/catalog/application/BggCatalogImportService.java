package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BoardGameMetadataMatching;
import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.catalog.BoardGameMetadataLinking;
import com.rulepilot.catalog.BoardGameMetadataLinking.Link;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class BggCatalogImportService implements BoardGameMetadataMatching, BoardGameMetadataLinking {

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

    @Override
    public List<Candidate> findExactCandidates(String playerFacingTitle) {
        String checked = checkedSearchQuery(playerFacingTitle);
        String expected = normalizedTitle(checked);
        return bgg.exactMatches(checked).stream()
                .limit(5)
                .map(match -> new Candidate(
                        match.bggId(),
                        match.name(),
                        match.publicationYear(),
                        match.coverUrl(),
                        match.minPlayers(),
                        match.maxPlayers(),
                        match.playingTimeMinutes(),
                        match.minimumAge(),
                        normalizedTitle(match.name()).equals(expected)))
                .toList();
    }

    public List<HotGame> hotGames() {
        return bgg.hotGames().stream()
                .filter(game -> !game.thumbnailUrl().isBlank())
                .limit(12)
                .toList();
    }

    public List<DiscoveryGame> recommendations(Integer players, Integer maxMinutes, BigDecimal maxWeight) {
        validateRecommendationFilters(players, maxMinutes, maxWeight);
        return bgg.hotGameDetails().stream()
                .filter(game -> players == null
                        || (game.minPlayers() != null
                                && game.maxPlayers() != null
                                && game.minPlayers() <= players
                                && game.maxPlayers() >= players))
                .filter(game -> maxMinutes == null
                        || (game.playingTimeMinutes() != null && game.playingTimeMinutes() <= maxMinutes))
                .filter(game -> maxWeight == null
                        || (game.averageWeight() != null && game.averageWeight().compareTo(maxWeight) <= 0))
                .limit(12)
                .toList();
    }

    public GameDetails gameDetails(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        return bgg.game(bggId);
    }

    private void validateRecommendationFilters(Integer players, Integer maxMinutes, BigDecimal maxWeight) {
        if (players != null && (players < 1 || players > 20)) {
            throw new IllegalArgumentException("players must be between 1 and 20");
        }
        if (maxMinutes != null && (maxMinutes < 15 || maxMinutes > 600)) {
            throw new IllegalArgumentException("maxMinutes must be between 15 and 600");
        }
        if (maxWeight != null
                && (maxWeight.compareTo(BigDecimal.ONE) < 0
                        || maxWeight.compareTo(BigDecimal.valueOf(5)) > 0)) {
            throw new IllegalArgumentException("maxWeight must be between 1 and 5");
        }
    }

    private String checkedSearchQuery(String query) {
        String checked = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        if (checked.length() < 2 || checked.length() > 120) {
            throw new IllegalArgumentException("BGG search query must contain 2 to 120 characters");
        }
        return checked;
    }

    private String normalizedTitle(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    @Transactional
    public ImportedGame importGame(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        return repository.findGameByBggId(bggId)
                .map(game -> existing(game, repository.findBggMetadata(game.id()).orElseThrow()))
                .orElseGet(() -> importNew(bgg.game(bggId)));
    }

    @Override
    @Transactional
    public Link confirm(int bggId) {
        ImportedGame imported = importGame(bggId);
        return new Link(
                imported.game().id(),
                imported.edition().id(),
                imported.metadata().bggId(),
                imported.game().name(),
                imported.metadata().thumbnailUrl(),
                imported.alreadyImported());
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
