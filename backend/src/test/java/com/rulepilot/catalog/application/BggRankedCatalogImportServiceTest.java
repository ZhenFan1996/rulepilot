package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BggRankedCatalogImportServiceTest {

    private static final String HEADERS = String.join(",", BggRankedCatalogImportService.EXPECTED_HEADERS);
    private final MemoryRepository repository = new MemoryRepository();
    private final BggRankedCatalogImportService service = new BggRankedCatalogImportService(
            repository,
            Clock.fixed(Instant.parse("2026-08-07T08:00:00Z"), ZoneOffset.UTC),
            2,
            10);

    @Test
    void importsTheOfficialSchemaWithoutChangingNamesRatingsOrTypeRanks() {
        String csv = HEADERS + "\n"
                + "266192,Wingspan,2019,34,7.79123,8.09123,102030,0,,,,12,,88,,\n"
                + "13,\"Catan, 5–6 Player Extension\",1996,Not Ranked,5.1,7.2,900,1,,,,,,,,\n";

        Snapshot snapshot = service.importDump(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "boardgames_ranks.csv");

        assertThat(snapshot.gameCount()).isEqualTo(2);
        assertThat(snapshot.importedAt()).isEqualTo("2026-08-07T08:00:00Z");
        assertThat(snapshot.sha256()).hasSize(64);
        assertThat(repository.published).isEqualTo(snapshot);
        assertThat(repository.staged).hasSize(2);
        RankedGame wingspan = repository.staged.getFirst();
        assertThat(wingspan.sourceName()).isEqualTo("Wingspan");
        assertThat(wingspan.averageRating()).isEqualByComparingTo("8.09123");
        assertThat(wingspan.typeRanks())
                .containsEntry(BggRankedCatalog.GameType.FAMILY, 12)
                .containsEntry(BggRankedCatalog.GameType.STRATEGY, 88);
        assertThat(repository.staged.get(1).sourceName()).isEqualTo("Catan, 5–6 Player Extension");
        assertThat(repository.staged.get(1).expansion()).isTrue();
    }

    @Test
    void rejectsAFileThatIsNotTheOfficialRankedCatalogShape() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.importDump(
                        new ByteArrayInputStream("id,name\n1,Fake\n".getBytes(StandardCharsets.UTF_8)),
                        "boardgames_ranks.csv"))
                .withMessageContaining("official schema");

        assertThat(repository.published).isNull();
    }

    private static final class MemoryRepository implements BggRankedCatalogRepository {
        private final List<RankedGame> staged = new ArrayList<>();
        private Snapshot published;

        @Override
        public Optional<Snapshot> findSnapshot() {
            return Optional.ofNullable(published);
        }

        @Override
        public Page find(Query query) {
            return new Page(staged.size(), query.page(), query.size(), staged);
        }

        @Override
        public void stage(UUID importId, List<RankedGame> games) {
            staged.addAll(games);
        }

        @Override
        public void publish(UUID importId, Snapshot snapshot) {
            published = snapshot;
        }
    }
}
