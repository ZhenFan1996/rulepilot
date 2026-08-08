package com.rulepilot.catalog.adapter.in.web;

import com.rulepilot.catalog.application.BggCatalogImportService;
import com.rulepilot.catalog.application.BggCatalogImportService.ImportedGame;
import com.rulepilot.catalog.application.BggMetadataLocalizationService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedMetadata;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedTaxonomy;
import com.rulepilot.catalog.application.BggCatalogImportService.DiscoveryPage;
import com.rulepilot.catalog.application.BggCatalogImportService.RecommendationSort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import com.rulepilot.catalog.application.GameCatalogService;
import com.rulepilot.catalog.application.GameCatalogView;
import com.rulepilot.catalog.application.SimplifiedChineseText;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@Profile("!test")
public class GameCatalogController {

    private final GameCatalogService catalogService;
    private final BggCatalogImportService bggService;
    private final BggMetadataLocalizationService metadataLocalization;

    public GameCatalogController(
            GameCatalogService catalogService,
            BggCatalogImportService bggService,
            BggMetadataLocalizationService metadataLocalization) {
        this.catalogService = catalogService;
        this.bggService = bggService;
        this.metadataLocalization = metadataLocalization;
    }

    @GetMapping("/games")
    List<GameResponse> listGames() {
        return catalogService.listCatalog().stream().map(GameResponse::from).toList();
    }

    @GetMapping("/bgg/status")
    BggStatus bggStatus() {
        return new BggStatus(bggService.configured());
    }

    @GetMapping("/bgg/search")
    List<BggSearchResult> searchBgg(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "en") String locale) {
        requireBgg();
        return bggService.search(query).stream().map(result -> BggSearchResult.from(result, locale)).toList();
    }

    @GetMapping("/bgg/hot")
    List<BggHotGameResponse> hotBggGames(@RequestParam(defaultValue = "en") String locale) {
        requireBgg();
        return bggService.hotGames().stream().map(game -> BggHotGameResponse.from(game, locale)).toList();
    }

    @GetMapping("/bgg/recommendations")
    List<BggRecommendationResponse> recommendBggGames(
            @RequestParam(required = false) Integer players,
            @RequestParam(required = false) Integer maxMinutes,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(defaultValue = "en") String locale) {
        requireBgg();
        return bggService.recommendations(players, maxMinutes, maxWeight).stream()
                .map(game -> BggRecommendationResponse.from(game, locale))
                .toList();
    }

    @GetMapping("/bgg/discovery")
    BggDiscoveryResponse discoverBggGames(
            @RequestParam(required = false) Integer players,
            @RequestParam(required = false) Integer maxMinutes,
            @RequestParam(required = false) BigDecimal maxWeight,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "en") String locale) {
        requireBgg();
        RecommendationSort requestedSort;
        try {
            requestedSort = RecommendationSort.valueOf(sort.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be hot or rating");
        }
        DiscoveryPage page = bggService.discovery(players, maxMinutes, maxWeight, category, requestedSort);
        LocalizedTaxonomy taxonomy = metadataLocalization.localizeDiscoveryCategories(page.categories(), locale);
        return BggDiscoveryResponse.from(page, taxonomy, locale, requestedSort);
    }

    @PostMapping("/bgg/games/{bggId}/import")
    BggImportResponse importBggGame(@PathVariable int bggId) {
        requireBgg();
        return BggImportResponse.from(bggService.importGame(bggId));
    }

    @GetMapping("/bgg/games/{bggId}")
    BggGameSelectionResponse bggGame(
            @PathVariable int bggId,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "true") boolean translate) {
        requireBgg();
        var game = bggService.gameDetails(bggId);
        LocalizedMetadata metadata = translate
                ? metadataLocalization.localize(game, locale)
                : metadataLocalization.sourceOnly(game, locale);
        return BggGameSelectionResponse.from(game, metadata);
    }

    @PostMapping("/games")
    @ResponseStatus(HttpStatus.CREATED)
    GameDetails createGame(@RequestBody CreateGameRequest request) {
        return GameDetails.from(catalogService.createGame(request.name()));
    }

    @PostMapping("/games/{gameId}/editions")
    @ResponseStatus(HttpStatus.CREATED)
    EditionDetails createEdition(@PathVariable UUID gameId, @RequestBody CreateEditionRequest request) {
        return EditionDetails.from(catalogService.createEdition(
                gameId, request.name(), request.language(), request.publicationYear()));
    }

    @PostMapping("/games/{gameId}/expansions")
    @ResponseStatus(HttpStatus.CREATED)
    ExpansionDetails createExpansion(@PathVariable UUID gameId, @RequestBody CreateExpansionRequest request) {
        return ExpansionDetails.from(catalogService.createExpansion(
                gameId, request.name(), request.compatibleEditionIds()));
    }

    record CreateGameRequest(String name) {}

    record CreateEditionRequest(String name, String language, Integer publicationYear) {}

    record CreateExpansionRequest(String name, Set<UUID> compatibleEditionIds) {}

    private void requireBgg() {
        if (!bggService.configured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "BGG application token is not configured");
        }
    }

    record BggStatus(boolean configured) {}

    record BggSearchResult(
            int bggId,
            String name,
            String originalName,
            boolean nameLocalized,
            Integer publicationYear,
            String bggUrl) {
        static BggSearchResult from(SearchResult result, String locale) {
            String displayName = BggMetadataLocalizationService.isSimplifiedChinese(locale)
                    ? SimplifiedChineseText.normalize(result.name())
                    : result.name();
            return new BggSearchResult(
                    result.bggId(),
                    displayName,
                    result.name(),
                    false,
                    result.publicationYear(),
                    "https://boardgamegeek.com/boardgame/" + result.bggId());
        }
    }

    record BggHotGameResponse(
            int rank,
            int bggId,
            String name,
            String originalName,
            boolean nameLocalized,
            Integer publicationYear,
            String thumbnailUrl,
            String bggUrl) {
        static BggHotGameResponse from(HotGame game, String locale) {
            String displayName = BggMetadataLocalizationService.isSimplifiedChinese(locale)
                    ? SimplifiedChineseText.normalize(game.name())
                    : game.name();
            return new BggHotGameResponse(
                    game.rank(),
                    game.bggId(),
                    displayName,
                    game.name(),
                    false,
                    game.publicationYear(),
                    game.thumbnailUrl(),
                    "https://boardgamegeek.com/boardgame/" + game.bggId());
        }
    }

    record BggRecommendationResponse(
            int rank,
            int bggId,
            String name,
            String originalName,
            boolean nameLocalized,
            Integer publicationYear,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            BigDecimal averageRating,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            String bggUrl) {
        static BggRecommendationResponse from(DiscoveryGame game, String locale) {
            boolean localized = BggMetadataLocalizationService.isSimplifiedChinese(locale)
                    && !game.chineseName().isBlank();
            return new BggRecommendationResponse(
                    game.rank(),
                    game.bggId(),
                    localized
                            ? game.chineseName()
                            : BggMetadataLocalizationService.isSimplifiedChinese(locale)
                                    ? SimplifiedChineseText.normalize(game.name())
                                    : game.name(),
                    game.name(),
                    localized,
                    game.publicationYear(),
                    game.thumbnailUrl(),
                    game.minPlayers(),
                    game.maxPlayers(),
                    game.playingTimeMinutes(),
                    game.averageRating(),
                    game.averageWeight(),
                    game.categories(),
                    game.mechanics(),
                    "https://boardgamegeek.com/boardgame/" + game.bggId());
        }
    }

    record BggDiscoveryResponse(
            int sourceCount,
            String sort,
            boolean categoriesTranslated,
            List<BggDiscoveryCategory> categories,
            List<BggRecommendationResponse> games) {
        static BggDiscoveryResponse from(
                DiscoveryPage page,
                LocalizedTaxonomy taxonomy,
                String locale,
                RecommendationSort sort) {
            return new BggDiscoveryResponse(
                    page.sourceCount(),
                    sort.name().toLowerCase(Locale.ROOT),
                    taxonomy.translated(),
                    page.categories().stream()
                            .map(source -> new BggDiscoveryCategory(source, taxonomy.categories().getOrDefault(source, source)))
                            .toList(),
                    page.games().stream().map(game -> BggRecommendationResponse.from(game, locale)).toList());
        }
    }

    record BggDiscoveryCategory(String value, String label) {}

    record BggImportResponse(
            GameDetails game,
            EditionDetails edition,
            int bggId,
            String description,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            String bggUrl,
            boolean alreadyImported) {
        static BggImportResponse from(ImportedGame imported) {
            var metadata = imported.metadata();
            return new BggImportResponse(
                    GameDetails.from(imported.game()),
                    EditionDetails.from(imported.edition()),
                    metadata.bggId(),
                    metadata.description(),
                    metadata.thumbnailUrl(),
                    metadata.minPlayers(),
                    metadata.maxPlayers(),
                    metadata.playingTimeMinutes(),
                    metadata.minimumAge(),
                    "https://boardgamegeek.com/boardgame/" + metadata.bggId(),
                    imported.alreadyImported());
        }
    }

    record BggGameSelectionResponse(
            int bggId,
            String name,
            String originalName,
            boolean officialNameLocalized,
            String description,
            String thumbnailUrl,
            Integer publicationYear,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            String imageUrl,
            BigDecimal averageRating,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers,
            boolean descriptionTranslated,
            boolean categoriesTranslated,
            boolean mechanicsTranslated,
            String bggUrl) {
        static BggGameSelectionResponse from(
                com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails game,
                LocalizedMetadata metadata) {
            return new BggGameSelectionResponse(
                    game.bggId(),
                    metadata.name(),
                    game.name(),
                    metadata.officialNameLocalized(),
                    metadata.description(),
                    game.thumbnailUrl(),
                    game.publicationYear(),
                    game.minPlayers(),
                    game.maxPlayers(),
                    game.playingTimeMinutes(),
                    game.minimumAge(),
                    game.imageUrl(),
                    game.averageRating(),
                    game.averageWeight(),
                    metadata.categories(),
                    metadata.mechanics(),
                    game.designers(),
                    game.publishers(),
                    metadata.descriptionTranslated(),
                    metadata.categoriesTranslated(),
                    metadata.mechanicsTranslated(),
                    "https://boardgamegeek.com/boardgame/" + game.bggId());
        }
    }

    record GameResponse(
            GameDetails game,
            List<EditionDetails> editions,
            List<ExpansionDetails> expansions,
            BggMetadataDetails bggMetadata) {
        static GameResponse from(GameCatalogView view) {
            return new GameResponse(
                    GameDetails.from(view.game()),
                    view.editions().stream().map(EditionDetails::from).toList(),
                    view.expansions().stream().map(ExpansionDetails::from).toList(),
                    view.bggMetadata().map(BggMetadataDetails::from).orElse(null));
        }
    }

    record BggMetadataDetails(
            int bggId,
            String description,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            String bggUrl) {
        static BggMetadataDetails from(BggGameMetadata metadata) {
            return new BggMetadataDetails(
                    metadata.bggId(),
                    metadata.description(),
                    metadata.thumbnailUrl(),
                    metadata.minPlayers(),
                    metadata.maxPlayers(),
                    metadata.playingTimeMinutes(),
                    metadata.minimumAge(),
                    "https://boardgamegeek.com/boardgame/" + metadata.bggId());
        }
    }

    record GameDetails(UUID id, String name, Instant createdAt) {
        static GameDetails from(Game game) {
            return new GameDetails(game.id(), game.name(), game.createdAt());
        }
    }

    record EditionDetails(UUID id, UUID gameId, String name, String language, Integer publicationYear) {
        static EditionDetails from(GameEdition edition) {
            return new EditionDetails(
                    edition.id(), edition.gameId(), edition.name(), edition.language(), edition.publicationYear());
        }
    }

    record ExpansionDetails(UUID id, UUID gameId, String name, Set<UUID> compatibleEditionIds) {
        static ExpansionDetails from(Expansion expansion) {
            return new ExpansionDetails(
                    expansion.id(), expansion.gameId(), expansion.name(), expansion.compatibleEditionIds());
        }
    }
}
