package com.rulepilot.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.application.BggMetadataLocalizationService;
import com.rulepilot.catalog.application.BggMetadataLocalizationService.LocalizedDiscoveryTaxonomy;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BggRankedCatalogImportService;
import com.rulepilot.catalog.application.BggRankedCatalogService;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseGame;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseResult;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BggRankedCatalogControllerTest {

    private final BggRankedCatalogService catalog = mock(BggRankedCatalogService.class);
    private final BggRankedCatalogImportService importer = mock(BggRankedCatalogImportService.class);
    private final BggMetadataLocalizationService localization = mock(BggMetadataLocalizationService.class);
    private final BggRankedCatalogController controller = new BggRankedCatalogController(catalog, importer, localization);

    @Test
    void exposesSnapshotCountsOfficialChineseNamesAndTranslatedTaxonomy() {
        RankedGame ranked = new RankedGame(
                266192,
                "Wingspan",
                2019,
                34,
                new BigDecimal("7.79"),
                new BigDecimal("8.09"),
                102_030,
                false,
                Map.of(BggGameType.STRATEGY, 88));
        DiscoveryGame details = new DiscoveryGame(
                1,
                266192,
                "Wingspan",
                "展翅翱翔",
                2019,
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                new BigDecimal("8.09"),
                new BigDecimal("2.5"),
                List.of("Animals"),
                List.of("Card Drafting"));
        Snapshot snapshot = new Snapshot(
                Instant.parse("2026-08-07T08:00:00Z"), LocalDate.parse("2026-08-07"), 162_686, "a".repeat(64));
        when(catalog.browse("", BggGameType.STRATEGY, Sort.RATING, 0, 20, true))
                .thenReturn(new BrowseResult(
                        Optional.of(snapshot), 7_543, 0, 20, Sort.RATING, BggGameType.STRATEGY,
                        List.of(new BrowseGame(ranked, 2, details))));
        when(localization.localizeDiscoveryTaxonomy(List.of("Animals"), List.of("Card Drafting"), "zh-CN"))
                .thenReturn(new LocalizedDiscoveryTaxonomy(
                        Map.of("Animals", "动物"), Map.of("Card Drafting", "卡牌轮抽"), true));

        var response = controller.catalog("", "strategy", "rating", 0, 20, "zh-CN", true);

        assertThat(response.ready()).isTrue();
        assertThat(response.sourceCount()).isEqualTo(162_686);
        assertThat(response.total()).isEqualTo(7_543);
        assertThat(response.games().getFirst().name()).isEqualTo("展翅翱翔");
        assertThat(response.games().getFirst().originalName()).isEqualTo("Wingspan");
        assertThat(response.games().getFirst().categories()).containsExactly("动物");
        assertThat(response.games().getFirst().mechanics()).containsExactly("卡牌轮抽");
    }

    @Test
    void rejectsUnsupportedSortsBeforeQueryingTheCatalog() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> controller.catalog("", "all", "popular", 0, 20, "en", false))
                .withMessage("sort is unsupported");
    }

    @Test
    void normalizesATraditionalSourceTitleWithoutClaimingAnOfficialLocalizedVersion() {
        RankedGame ranked = new RankedGame(
                42,
                "奇幻寶島",
                2026,
                10,
                new BigDecimal("7.0"),
                new BigDecimal("7.5"),
                100,
                false,
                Map.of(BggGameType.FAMILY, 1));
        when(catalog.browse("", BggGameType.ALL, Sort.RANK, 0, 20, false))
                .thenReturn(new BrowseResult(Optional.empty(), 1, 0, 20, Sort.RANK, BggGameType.ALL,
                        List.of(new BrowseGame(ranked, null, null))));
        when(localization.sourceDiscoveryTaxonomy(List.of(), List.of()))
                .thenReturn(new LocalizedDiscoveryTaxonomy(Map.of(), Map.of(), false));

        var response = controller.catalog("", "all", "rank", 0, 20, "zh-CN", false);

        assertThat(response.games().getFirst().name()).isEqualTo("奇幻宝岛");
        assertThat(response.games().getFirst().originalName()).isEqualTo("奇幻寶島");
        assertThat(response.games().getFirst().nameLocalized()).isFalse();
        verify(localization, never()).localizeDiscoveryTaxonomy(List.of(), List.of(), "zh-CN");
    }
}
