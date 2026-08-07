package com.rulepilot.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.application.BggCatalogImportService;
import com.rulepilot.catalog.application.BggDescriptionLocalizationService;
import com.rulepilot.catalog.application.BggDescriptionLocalizationService.LocalizedDescription;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.GameCatalogService;
import java.math.BigDecimal;
import java.util.List;
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
                mock(GameCatalogService.class), bgg, mock(BggDescriptionLocalizationService.class));

        var response = controller.recommendBggGames(4, 90, new BigDecimal("3.0"));

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().rank()).isEqualTo(2);
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
        BggDescriptionLocalizationService descriptions = mock(BggDescriptionLocalizationService.class);
        when(descriptions.localize(bgg.gameDetails(266192), "zh-CN"))
                .thenReturn(new LocalizedDescription("建造一座鸟类保护区。", true));
        GameCatalogController controller = new GameCatalogController(mock(GameCatalogService.class), bgg, descriptions);

        var response = controller.bggGame(266192, "zh-CN");

        assertThat(response.name()).isEqualTo("Wingspan");
        assertThat(response.description()).isEqualTo("建造一座鸟类保护区。");
        assertThat(response.descriptionTranslated()).isTrue();
        assertThat(response.minimumAge()).isEqualTo(10);
        assertThat(response.bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/266192");
    }
}
