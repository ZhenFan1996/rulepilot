package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggDescriptionTranslation;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggDescriptionLocalizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggDescriptionLocalizationService.class);

    private final BggDescriptionTranslation translations;

    public BggDescriptionLocalizationService(BggDescriptionTranslation translations) {
        this.translations = translations;
    }

    public LocalizedDescription localize(BoardGameGeekCatalog.GameDetails game, String requestedLocale) {
        String source = game.description() == null ? "" : game.description().strip();
        if (source.isBlank() || !isSimplifiedChinese(requestedLocale)) {
            return new LocalizedDescription(source, false);
        }
        try {
            return translations.translate(game.bggId(), game.name(), source)
                    .filter(translation -> !translation.isBlank())
                    .map(translation -> new LocalizedDescription(translation.strip(), true))
                    .orElseGet(() -> new LocalizedDescription(source, false));
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG description translation fell back to source text for bggId={}", game.bggId());
            return new LocalizedDescription(source, false);
        }
    }

    private boolean isSimplifiedChinese(String requestedLocale) {
        String locale = requestedLocale == null ? "" : requestedLocale.strip().toLowerCase(Locale.ROOT);
        return locale.equals("zh") || locale.equals("zh-cn") || locale.equals("zh-hans");
    }

    public record LocalizedDescription(String text, boolean translated) {}
}
