package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BggCatalogImportServiceTest {

    @Test
    void importsGameAndEditionOnceForSameBggId() {
        FakeBgg bgg = new FakeBgg();
        MemoryCatalogRepository repository = new MemoryCatalogRepository();
        BggCatalogImportService service = new BggCatalogImportService(
                bgg,
                repository,
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        var first = service.importGame(266192);
        var repeated = service.importGame(266192);

        assertThat(first.alreadyImported()).isFalse();
        assertThat(repeated.alreadyImported()).isTrue();
        assertThat(repeated.game().id()).isEqualTo(first.game().id());
        assertThat(first.edition().language()).isEqualTo("und");
        assertThat(repository.games).hasSize(1);
        assertThat(repository.editions).hasSize(1);
        assertThat(bgg.detailCalls).isEqualTo(1);
    }

    private static final class FakeBgg implements BoardGameGeekCatalog {
        int detailCalls;

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            return List.of(new SearchResult(266192, "Wingspan", 2019));
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of(new HotGame(1, 266192, "Wingspan", 2019, "https://example.test/wingspan.jpg"));
        }

        @Override
        public GameDetails game(int bggId) {
            detailCalls++;
            return new GameDetails(bggId, "Wingspan", "Bird game", "", 2019, 1, 5, 70, 10);
        }
    }

    private static final class MemoryCatalogRepository implements CatalogRepository {
        final List<Game> games = new ArrayList<>();
        final List<GameEdition> editions = new ArrayList<>();
        final List<BggGameMetadata> metadata = new ArrayList<>();

        @Override
        public Game save(Game game) {
            games.add(game);
            return game;
        }

        @Override
        public GameEdition save(GameEdition edition) {
            editions.add(edition);
            return edition;
        }

        @Override
        public Expansion save(Expansion expansion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BggGameMetadata save(BggGameMetadata value) {
            metadata.add(value);
            return value;
        }

        @Override
        public Optional<Game> findGame(UUID gameId) {
            return games.stream().filter(game -> game.id().equals(gameId)).findFirst();
        }

        @Override
        public Optional<GameEdition> findEdition(UUID editionId) {
            return editions.stream().filter(edition -> edition.id().equals(editionId)).findFirst();
        }

        @Override
        public Optional<Game> findGameByBggId(int bggId) {
            return metadata.stream()
                    .filter(value -> value.bggId() == bggId)
                    .findFirst()
                    .flatMap(value -> findGame(value.gameId()));
        }

        @Override
        public Optional<BggGameMetadata> findBggMetadata(UUID gameId) {
            return metadata.stream().filter(value -> value.gameId().equals(gameId)).findFirst();
        }

        @Override
        public List<Game> findGames() {
            return List.copyOf(games);
        }

        @Override
        public List<GameEdition> findEditions(UUID gameId) {
            return editions.stream().filter(value -> value.gameId().equals(gameId)).toList();
        }

        @Override
        public List<Expansion> findExpansions(UUID gameId) {
            return List.of();
        }
    }
}
