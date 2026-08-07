package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggDescriptionTranslation;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BggDescriptionLocalizationServiceTest {

    private final BggDescriptionTranslation translations = mock(BggDescriptionTranslation.class);
    private final BggDescriptionLocalizationService service = new BggDescriptionLocalizationService(translations);

    @Test
    void translatesBggDescriptionsForSimplifiedChineseReaders() {
        var game = game("Build a bird reserve.");
        when(translations.translate(266192, "Wingspan", game.description()))
                .thenReturn(Optional.of("建造一座鸟类保护区。"));

        var localized = service.localize(game, "zh-CN");

        assertThat(localized.text()).isEqualTo("建造一座鸟类保护区。");
        assertThat(localized.translated()).isTrue();
    }

    @Test
    void preservesTheBggSourceForEnglishReaders() {
        var game = game("Build a bird reserve.");

        var localized = service.localize(game, "en");

        assertThat(localized.text()).isEqualTo(game.description());
        assertThat(localized.translated()).isFalse();
        verify(translations, never()).translate(266192, "Wingspan", game.description());
    }

    @Test
    void fallsBackToTheBggSourceWhenTranslationIsUnavailable() {
        var game = game("Build a bird reserve.");
        when(translations.translate(266192, "Wingspan", game.description()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        var localized = service.localize(game, "zh-Hans");

        assertThat(localized.text()).isEqualTo(game.description());
        assertThat(localized.translated()).isFalse();
    }

    private BoardGameGeekCatalog.GameDetails game(String description) {
        return new BoardGameGeekCatalog.GameDetails(
                266192, "Wingspan", description, "https://example.test/wingspan.jpg", 2019, 1, 5, 70, 10);
    }
}
