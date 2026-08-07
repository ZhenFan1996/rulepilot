package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggMetadataTranslation.Request;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggMetadataLocalizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggMetadataLocalizationService.class);

    private final BggMetadataTranslation translations;

    public BggMetadataLocalizationService(BggMetadataTranslation translations) {
        this.translations = translations;
    }

    public LocalizedMetadata localize(BoardGameGeekCatalog.GameDetails game, String requestedLocale) {
        String description = game.description() == null ? "" : game.description().strip();
        if (!isSimplifiedChinese(requestedLocale)) {
            return source(game, game.name(), false, description);
        }

        String localizedName = game.officialChineseNames().stream().findFirst().orElse(game.name());
        boolean officialNameLocalized = !localizedName.equals(game.name());
        LocalizedMetadata fallback = source(game, localizedName, officialNameLocalized, description);
        if (description.isBlank() && game.categories().isEmpty() && game.mechanics().isEmpty()) return fallback;

        Request request = new Request(
                game.bggId(), localizedName, description, game.categories(), game.mechanics());
        try {
            return translations.translate(request)
                    .filter(translation -> validFor(request, translation))
                    .map(translation -> localized(fallback, request, translation))
                    .orElse(fallback);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG metadata translation fell back to source values for bggId={}", game.bggId());
            return fallback;
        }
    }

    public static boolean isSimplifiedChinese(String requestedLocale) {
        String locale = requestedLocale == null ? "" : requestedLocale.strip().toLowerCase(Locale.ROOT);
        return locale.equals("zh") || locale.equals("zh-cn") || locale.equals("zh-hans");
    }

    private LocalizedMetadata source(
            BoardGameGeekCatalog.GameDetails game,
            String name,
            boolean officialNameLocalized,
            String description) {
        return new LocalizedMetadata(
                name,
                officialNameLocalized,
                description,
                false,
                game.categories(),
                false,
                game.mechanics(),
                false);
    }

    private boolean validFor(Request request, Translation translation) {
        return translation.description() != null
                && (request.description().isBlank() == translation.description().isBlank())
                && sameShape(request.categories(), translation.categories())
                && sameShape(request.mechanics(), translation.mechanics());
    }

    private boolean sameShape(List<String> source, List<String> translated) {
        return translated != null
                && translated.size() == source.size()
                && translated.stream().allMatch(value -> value != null && !value.isBlank() && value.length() <= 200);
    }

    private LocalizedMetadata localized(
            LocalizedMetadata fallback,
            Request request,
            Translation translation) {
        String description = translation.description().strip();
        List<String> categories = translation.categories().stream().map(String::strip).toList();
        List<String> mechanics = translation.mechanics().stream().map(String::strip).toList();
        return new LocalizedMetadata(
                fallback.name(),
                fallback.officialNameLocalized(),
                description,
                !description.equals(request.description()),
                categories,
                !categories.equals(request.categories()),
                mechanics,
                !mechanics.equals(request.mechanics()));
    }

    public record LocalizedMetadata(
            String name,
            boolean officialNameLocalized,
            String description,
            boolean descriptionTranslated,
            List<String> categories,
            boolean categoriesTranslated,
            List<String> mechanics,
            boolean mechanicsTranslated) {
        public LocalizedMetadata {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
        }
    }
}
