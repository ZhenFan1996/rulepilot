package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BggMetadataLocalizationServiceTest {

    private final BggMetadataTranslation translations = mock(BggMetadataTranslation.class);
    private final BggMetadataLocalizationService service = new BggMetadataLocalizationService(translations);

    @Test
    void usesTheBggChineseEditionNameAndTranslatesStructuredMetadata() {
        var game = game(List.of("展翅翱翔"));
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "建造一座鸟类保护区。", List.of("动物"), List.of("卡牌轮抽"))));

        var localized = service.localize(game, "zh-CN");

        assertThat(localized.name()).isEqualTo("展翅翱翔");
        assertThat(localized.officialNameLocalized()).isTrue();
        assertThat(localized.description()).isEqualTo("建造一座鸟类保护区。");
        assertThat(localized.categories()).containsExactly("动物");
        assertThat(localized.mechanics()).containsExactly("卡牌轮抽");
        assertThat(localized.descriptionTranslated()).isTrue();
        assertThat(localized.categoriesTranslated()).isTrue();
        assertThat(localized.mechanicsTranslated()).isTrue();
    }

    @Test
    void preservesEveryBggSourceValueForEnglishReaders() {
        var game = game(List.of("展翅翱翔"));

        var localized = service.localize(game, "en");

        assertThat(localized.name()).isEqualTo("Wingspan");
        assertThat(localized.description()).isEqualTo(game.description());
        assertThat(localized.categories()).isEqualTo(game.categories());
        assertThat(localized.mechanics()).isEqualTo(game.mechanics());
        assertThat(localized.officialNameLocalized()).isFalse();
        verify(translations, never()).translate(any());
    }

    @Test
    void exposesTheOfficialChineseEditionAndFullSourceMetadataWithoutWaitingForTranslation() {
        var game = game(List.of("展翅翡翔"));

        var localized = service.sourceOnly(game, "zh-CN");

        assertThat(localized.name()).isEqualTo("展翅翡翔");
        assertThat(localized.officialNameLocalized()).isTrue();
        assertThat(localized.description()).isEqualTo("Build a bird reserve.");
        assertThat(localized.categories()).containsExactly("Animals");
        assertThat(localized.mechanics()).containsExactly("Card Drafting");
        assertThat(localized.descriptionTranslated()).isFalse();
        verify(translations, never()).translate(any());
    }

    @Test
    void decodesBggDescriptionEntitiesAsSafePlainText() {
        var game = game(List.of(), "Build&nbsp;a reserve.&lt;br/&gt;Win &mdash; together.");

        var localized = service.sourceOnly(game, "en");

        assertThat(localized.description()).isEqualTo("Build a reserve.\nWin — together.");
        verify(translations, never()).translate(any());
    }

    @Test
    void keepsValidTranslatedFieldsWhenOneOptionalListHasTheWrongShape() {
        var game = game(List.of("展翅翱翔"));
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "建造一座鸟类保护区。", List.of(), List.of("卡牌轮抽"))));

        var localized = service.localize(game, "zh-Hans");

        assertThat(localized.name()).isEqualTo("展翅翱翔");
        assertThat(localized.officialNameLocalized()).isTrue();
        assertThat(localized.description()).isEqualTo("建造一座鸟类保护区。");
        assertThat(localized.categories()).isEqualTo(game.categories());
        assertThat(localized.mechanics()).containsExactly("卡牌轮抽");
        assertThat(localized.descriptionTranslated()).isTrue();
        assertThat(localized.categoriesTranslated()).isFalse();
        assertThat(localized.mechanicsTranslated()).isTrue();
    }

    @Test
    void doesNotInventAChineseTitleWhenBggHasNoChineseEditionName() {
        var game = game(List.of());
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "建造一座鸟类保护区。", List.of("动物"), List.of("卡牌轮抽"))));

        var localized = service.localize(game, "zh-CN");

        assertThat(localized.name()).isEqualTo("Wingspan");
        assertThat(localized.officialNameLocalized()).isFalse();
    }

    @Test
    void normalizesAnOfficialTraditionalNameAndTraditionalPrimaryFallbackForZhCnOnly() {
        var official = game("Wingspan", List.of("鳥翼寶島"));
        var fallback = game("奇幻寶島", List.of());

        var localizedOfficial = service.sourceOnly(official, "zh-CN");
        var localizedFallback = service.sourceOnly(fallback, "zh-CN");

        assertThat(localizedOfficial.name()).isEqualTo("鸟翼宝岛");
        assertThat(localizedOfficial.officialNameLocalized()).isTrue();
        assertThat(localizedFallback.name()).isEqualTo("奇幻宝岛");
        assertThat(localizedFallback.officialNameLocalized()).isFalse();
        assertThat(service.sourceOnly(fallback, "en").name()).isEqualTo("奇幻寶島");
    }

    @Test
    void translatesTheBoundedDiscoveryCategoryVocabularyInOneRequest() {
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "", List.of("家庭", "策略"), List.of())));

        var localized = service.localizeDiscoveryCategories(List.of("Family", "Strategy", "Family"), "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Family", "家庭").containsEntry("Strategy", "策略");
        verify(translations).translate(any());
    }

    @Test
    void normalizesTraditionalTranslationOutputAtTheApplicationBoundary() {
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "建立一座鳥類保護區。", List.of("動物"), List.of("卡牌輪抽"))));
        var source = game(List.of(), "Build a bird reserve.");

        var localized = service.localize(source, "zh-CN");

        assertThat(localized.description()).isEqualTo("建立一座鸟类保护区。");
        assertThat(localized.categories()).containsExactly("动物");
        assertThat(localized.mechanics()).containsExactly("卡牌轮抽");
    }

    @Test
    void translatesRankedCatalogCategoriesAndMechanicsInOneBoundedRequest() {
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "", List.of("策略"), List.of("牌库构筑"))));

        var localized = service.localizeDiscoveryTaxonomy(
                List.of("Strategy"), List.of("Deck Building"), "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Strategy", "策略");
        assertThat(localized.mechanics()).containsEntry("Deck Building", "牌库构筑");
        verify(translations).translate(any());
    }

    @Test
    void exposesOnlyTheBoundedPresentationPortToTheRecommendationModule() {
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "", List.of("动物"), List.of("卡牌轮抽"))));
        var presentation = new BggRecommendationPresentationService(service);

        var taxonomy = presentation.localizeTaxonomy(
                List.of("Animals"), List.of("Card Drafting"), "zh-CN");

        assertThat(presentation.usesSimplifiedChinese("zh-Hans")).isTrue();
        assertThat(presentation.normalizeSourceName("遊戲說明")).isEqualTo("游戏说明");
        assertThat(taxonomy.categories()).containsEntry("Animals", "动物");
        assertThat(taxonomy.mechanics()).containsEntry("Card Drafting", "卡牌轮抽");
    }

    @Test
    void chunksALargeRankedCatalogTaxonomyAndPublishesItOnlyWhenEveryChunkIsValid() {
        List<String> mechanics = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(index -> "Mechanic " + index)
                .toList();
        when(translations.translate(any())).thenAnswer(invocation -> {
            BggMetadataTranslation.Request request = invocation.getArgument(0);
            return Optional.of(new Translation(
                    "",
                    request.categories().stream().map(value -> "译:" + value).toList(),
                    request.mechanics().stream().map(value -> "译:" + value).toList()));
        });

        var localized = service.localizeDiscoveryTaxonomy(List.of("Strategy"), mechanics, "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Strategy", "译:Strategy");
        assertThat(localized.mechanics()).containsEntry("Mechanic 51", "译:Mechanic 51");
        verify(translations, times(2)).translate(any());
    }

    @Test
    void keepsTranslatedTaxonomyChunksWhenALaterOptionalChunkIsUnavailable() {
        List<String> mechanics = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(index -> "Mechanic " + index)
                .toList();
        when(translations.translate(any())).thenAnswer(invocation -> {
            BggMetadataTranslation.Request request = invocation.getArgument(0);
            if (request.mechanics().contains("Mechanic 50")) return Optional.empty();
            return Optional.of(new Translation(
                    "",
                    request.categories().stream().map(value -> "译:" + value).toList(),
                    request.mechanics().stream().map(value -> "译:" + value).toList()));
        });

        var localized = service.localizeDiscoveryTaxonomy(List.of("Strategy"), mechanics, "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Strategy", "译:Strategy");
        assertThat(localized.mechanics())
                .containsEntry("Mechanic 1", "译:Mechanic 1")
                .containsEntry("Mechanic 51", "Mechanic 51");
        verify(translations, times(2)).translate(any());
    }

    @Test
    void doesNotRejectAnOtherwiseValidTaxonomyTermAtAnArbitraryCharacterCount() {
        String longTranslation = "策略".repeat(120);
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "", List.of(longTranslation), List.of())));

        var localized = service.localizeDiscoveryCategories(List.of("Strategy"), "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Strategy", longTranslation);
    }

    private BoardGameGeekCatalog.GameDetails game(List<String> officialChineseNames) {
        return game(officialChineseNames, "Build a bird reserve.");
    }

    private BoardGameGeekCatalog.GameDetails game(List<String> officialChineseNames, String description) {
        return game("Wingspan", officialChineseNames, description);
    }

    private BoardGameGeekCatalog.GameDetails game(String name, List<String> officialChineseNames) {
        return game(name, officialChineseNames, "Build a bird reserve.");
    }

    private BoardGameGeekCatalog.GameDetails game(
            String name, List<String> officialChineseNames, String description) {
        return new BoardGameGeekCatalog.GameDetails(
                266192,
                name,
                description,
                "https://example.test/wingspan.jpg",
                2019,
                1,
                5,
                70,
                10,
                "",
                null,
                null,
                List.of("Animals"),
                List.of("Card Drafting"),
                List.of("Elizabeth Hargrave"),
                List.of("Stonemaier Games"),
                officialChineseNames);
    }
}
