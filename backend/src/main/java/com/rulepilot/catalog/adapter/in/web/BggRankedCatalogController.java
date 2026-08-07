package com.rulepilot.catalog.adapter.in.web;

import com.rulepilot.catalog.application.BggMetadataLocalizationService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedDiscoveryTaxonomy;
import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BggRankedCatalogImportService;
import com.rulepilot.catalog.application.BggRankedCatalogService;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseGame;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseResult;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!test")
public class BggRankedCatalogController {

    private final BggRankedCatalogService catalog;
    private final BggRankedCatalogImportService importer;
    private final BggMetadataLocalizationService localization;

    public BggRankedCatalogController(
            BggRankedCatalogService catalog,
            BggRankedCatalogImportService importer,
            BggMetadataLocalizationService localization) {
        this.catalog = catalog;
        this.importer = importer;
        this.localization = localization;
    }

    @GetMapping("/api/v1/bgg/catalog")
    CatalogResponse catalog(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "true") boolean enrich) {
        BrowseResult result = catalog.browse(
                q,
                enumValue(GameType.class, type, "type"),
                enumValue(Sort.class, sort, "sort"),
                page,
                size,
                enrich);
        List<String> categories = result.games().stream()
                .map(BrowseGame::details)
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.categories().stream())
                .distinct()
                .toList();
        List<String> mechanics = result.games().stream()
                .map(BrowseGame::details)
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.mechanics().stream())
                .distinct()
                .toList();
        LocalizedDiscoveryTaxonomy taxonomy = localization.localizeDiscoveryTaxonomy(categories, mechanics, locale);
        return CatalogResponse.from(result, taxonomy, locale);
    }

    @PostMapping("/api/admin/bgg/ranked-catalog")
    @ResponseStatus(HttpStatus.CREATED)
    SnapshotResponse importCatalog(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("BGG ranked catalog file is required");
        try {
            return SnapshotResponse.from(importer.importDump(file.getInputStream(), file.getOriginalFilename()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("BGG ranked catalog file could not be read", exception);
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " is unsupported");
        }
    }

    record CatalogResponse(
            boolean ready,
            int sourceCount,
            long total,
            int page,
            int size,
            int totalPages,
            String sort,
            String type,
            Instant importedAt,
            LocalDate sourceDate,
            boolean taxonomyTranslated,
            List<CatalogGameResponse> games) {
        static CatalogResponse from(
                BrowseResult result, LocalizedDiscoveryTaxonomy taxonomy, String locale) {
            Snapshot snapshot = result.snapshot().orElse(null);
            return new CatalogResponse(
                    snapshot != null,
                    snapshot == null ? 0 : snapshot.gameCount(),
                    result.total(),
                    result.page(),
                    result.size(),
                    result.total() == 0 ? 0 : (int) ((result.total() + result.size() - 1) / result.size()),
                    result.sort().name().toLowerCase(Locale.ROOT),
                    result.type().name().toLowerCase(Locale.ROOT),
                    snapshot == null ? null : snapshot.importedAt(),
                    snapshot == null ? null : snapshot.sourceDate(),
                    taxonomy.translated(),
                    result.games().stream()
                            .map(game -> CatalogGameResponse.from(game, taxonomy, locale))
                            .toList());
        }
    }

    record CatalogGameResponse(
            int bggId,
            String name,
            String originalName,
            boolean nameLocalized,
            Integer publicationYear,
            Integer overallRank,
            Integer hotRank,
            BigDecimal geekRating,
            BigDecimal averageRating,
            int usersRated,
            boolean expansion,
            List<String> types,
            boolean detailsAvailable,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            String bggUrl) {
        static CatalogGameResponse from(
                BrowseGame game, LocalizedDiscoveryTaxonomy taxonomy, String locale) {
            DiscoveryGame details = game.details();
            boolean localized = details != null
                    && BggMetadataLocalizationService.isSimplifiedChinese(locale)
                    && !details.chineseName().isBlank();
            String sourceName = game.ranked().sourceName();
            return new CatalogGameResponse(
                    game.ranked().bggId(),
                    localized ? details.chineseName() : sourceName,
                    sourceName,
                    localized,
                    game.ranked().publicationYear(),
                    game.ranked().overallRank(),
                    game.hotRank(),
                    game.ranked().bayesAverage(),
                    game.ranked().averageRating(),
                    game.ranked().usersRated(),
                    game.ranked().expansion(),
                    game.ranked().types().stream().map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
                    details != null,
                    details == null ? "" : details.thumbnailUrl(),
                    details == null ? null : details.minPlayers(),
                    details == null ? null : details.maxPlayers(),
                    details == null ? null : details.playingTimeMinutes(),
                    details == null ? null : details.averageWeight(),
                    details == null
                            ? List.of()
                            : translated(details.categories(), taxonomy.categories()),
                    details == null
                            ? List.of()
                            : translated(details.mechanics(), taxonomy.mechanics()),
                    "https://boardgamegeek.com/boardgame/" + game.ranked().bggId());
        }

        private static List<String> translated(List<String> values, Map<String, String> translations) {
            return values.stream().map(value -> translations.getOrDefault(value, value)).toList();
        }
    }

    record SnapshotResponse(Instant importedAt, LocalDate sourceDate, int gameCount, String sha256) {
        static SnapshotResponse from(Snapshot snapshot) {
            return new SnapshotResponse(
                    snapshot.importedAt(), snapshot.sourceDate(), snapshot.gameCount(), snapshot.sha256());
        }
    }
}
