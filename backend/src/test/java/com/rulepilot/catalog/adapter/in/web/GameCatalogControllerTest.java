package com.rulepilot.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.application.BggCatalogImportService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedMetadata;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedTaxonomy;
import com.rulepilot.catalog.application.BggCatalogImportService.DiscoveryPage;
import com.rulepilot.catalog.application.BggCatalogImportService.RecommendationSort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.GameCatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameCatalogControllerTest {

    @Test
    void exposesAttributedPlayerFitRecommendations() {
        BggCatalogImportService bgg = mock(BggCatalogImportService.class);
        when(bgg.configured()).thenReturn(true);
        when(bgg.recommendations(4, 90, new BigDecimal("3.0")))
                .thenReturn(List.of(new DiscoveryGame(
                        2,
                        1002,
                        "Fitting Game",
                        "合适的游戏",
                        2025,
                        "https://example.test/fitting.jpg",
                        2,
                        5,
                        75,
                        new BigDecimal("7.6"),
                        new BigDecimal("2.7"),
                        List.of("Family"),
                        List.of("Set Collection"))));
        GameCatalogController controller = new GameCatalogController(
                mock(GameCatalogService.class), bgg, mock(BggMetadataLocalizationService.class));

        var response = controller.recommendBggGames(4, 90, new BigDecimal("3.0"), "zh-CN");

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().rank()).isEqualTo(2);
        assertThat(response.getFirst().name()).isEqualTo("合适的游戏");
        assertThat(response.getFirst().originalName()).isEqualTo("Fitting Game");
        assertThat(response.getFirst().nameLocalized()).isTrue();
        assertThat(response.getFirst().averageWeight()).isEqualByComparingTo("2.7");
        assertThat(response.getFirst().bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/1002");
    }

    @Test
    void exposesReadOnlyAttributedGameSelectionDetails() {
        BggCatalogImportService bgg = mock(BggCatalogImportService.class);
        when(bgg.configured()).thenReturn(true);
        when(bgg.gameDetails(266192)).thenReturn(new GameDetails(
                266192, "Wingspan", "Build a bird reserve.", "https://example.test/wingspan.jpg",
                2019, 1, 5, 70, 10));
        BggMetadataLocalizationService metadata = mock(BggMetadataLocalizationService.class);
        when(metadata.localize(bgg.gameDetails(266192), "zh-CN"))
                .thenReturn(new LocalizedMetadata(
                        "展翅翱翔",
                        true,
                        "建造一座鸟类保护区。",
                        true,
                        List.of("动物"),
                        true,
                        List.of("卡牌轮抽"),
                        true));
        GameCatalogController controller = new GameCatalogController(mock(GameCatalogService.class), bgg, metadata);

        var response = controller.bggGame(266192, "zh-CN", true);

        assertThat(response.name()).isEqualTo("展翅翱翔");
        assertThat(response.originalName()).isEqualTo("Wingspan");
        assertThat(response.officialNameLocalized()).isTrue();
        assertThat(response.description()).isEqualTo("建造一座鸟类保护区。");
        assertThat(response.descriptionTranslated()).isTrue();
        assertThat(response.categories()).containsExactly("动物");
        assertThat(response.mechanics()).containsExactly("卡牌轮抽");
        assertThat(response.minimumAge()).isEqualTo(10);
        assertThat(response.bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/266192");
    }

    @Test
    void exposesLocalizedDiscoveryCategoriesAndRequestedRatingOrder() {
        BggCatalogImportService bgg = mock(BggCatalogImportService.class);
        when(bgg.configured()).thenReturn(true);
        DiscoveryGame game = new DiscoveryGame(
                2, 1002, "Fitting Game", "合适的游戏", 2025, "https://example.test/fitting.jpg",
                2, 5, 75, new BigDecimal("7.6"), new BigDecimal("2.7"),
                List.of("Family"), List.of("Set Collection"));
        when(bgg.discovery(null, null, null, "Family", RecommendationSort.RATING))
                .thenReturn(new DiscoveryPage(12, List.of("Family", "Strategy"), List.of(game)));
        BggMetadataLocalizationService metadata = mock(BggMetadataLocalizationService.class);
        when(metadata.localizeDiscoveryCategories(List.of("Family", "Strategy"), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of("Family", "家庭", "Strategy", "策略"), true));
        GameCatalogController controller = new GameCatalogController(mock(GameCatalogService.class), bgg, metadata);

        var response = controller.discoverBggGames(null, null, null, "Family", "rating", "zh-CN");

        assertThat(response.sourceCount()).isEqualTo(12);
        assertThat(response.sort()).isEqualTo("rating");
        assertThat(response.categoriesTranslated()).isTrue();
        assertThat(response.categories()).extracting(GameCatalogController.BggDiscoveryCategory::label)
                .containsExactly("家庭", "策略");
        assertThat(response.games().getFirst().name()).isEqualTo("合适的游戏");
    }
}
