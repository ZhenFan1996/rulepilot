package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void retainsTheOfficialNameButFallsBackOnInvalidModelMetadata() {
        var game = game(List.of("展翅翱翔"));
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "建造一座鸟类保护区。", List.of(), List.of("卡牌轮抽"))));

        var localized = service.localize(game, "zh-Hans");

        assertThat(localized.name()).isEqualTo("展翅翱翔");
        assertThat(localized.officialNameLocalized()).isTrue();
        assertThat(localized.description()).isEqualTo(game.description());
        assertThat(localized.categories()).isEqualTo(game.categories());
        assertThat(localized.mechanics()).isEqualTo(game.mechanics());
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
    void translatesTheBoundedDiscoveryCategoryVocabularyInOneRequest() {
        when(translations.translate(any())).thenReturn(Optional.of(new Translation(
                "", List.of("家庭", "策略"), List.of())));

        var localized = service.localizeDiscoveryCategories(List.of("Family", "Strategy", "Family"), "zh-CN");

        assertThat(localized.translated()).isTrue();
        assertThat(localized.categories()).containsEntry("Family", "家庭").containsEntry("Strategy", "策略");
        verify(translations).translate(any());
    }

    private BoardGameGeekCatalog.GameDetails game(List<String> officialChineseNames) {
        return new BoardGameGeekCatalog.GameDetails(
                266192,
                "Wingspan",
                "Build a bird reserve.",
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
