package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggMetadataTranslation.Request;
import com.rulepilot.catalog.BggMetadataTranslation.Translation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@Profile("!test")
public class BggMetadataLocalizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggMetadataLocalizationService.class);
    private static final int DISCOVERY_TAXONOMY_ID = Integer.MAX_VALUE;
    private static final int RANKED_CATALOG_TAXONOMY_ID = Integer.MAX_VALUE - 1;
    private static final int MAX_DISCOVERY_CATEGORIES = 50;
    private static final int MAX_RANKED_CATALOG_TERMS = 160;
    private static final int TRANSLATION_CHUNK_SIZE = 50;

    private final BggMetadataTranslation translations;

    public BggMetadataLocalizationService(BggMetadataTranslation translations) {
        this.translations = translations;
    }

    public LocalizedMetadata localize(BoardGameGeekCatalog.GameDetails game, String requestedLocale) {
        LocalizedMetadata fallback = sourceOnly(game, requestedLocale);
        String description = fallback.description();
        if (!isSimplifiedChinese(requestedLocale)) return fallback;
        if (description.isBlank() && game.categories().isEmpty() && game.mechanics().isEmpty()) return fallback;

        Request request = new Request(
                game.bggId(), fallback.name(), description, game.categories(), game.mechanics());
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

    public LocalizedMetadata sourceOnly(BoardGameGeekCatalog.GameDetails game, String requestedLocale) {
        String description = normalizedDescription(game.description());
        String name = game.name();
        boolean officialNameLocalized = false;
        if (isSimplifiedChinese(requestedLocale)) {
            name = game.officialChineseNames().stream().findFirst().orElse(game.name());
            officialNameLocalized = !name.equals(game.name());
        }
        return source(game, name, officialNameLocalized, description);
    }

    private String normalizedDescription(String source) {
        if (source == null || source.isBlank()) return "";
        return HtmlUtils.htmlUnescape(source)
                .replace('\u00a0', ' ')
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .strip();
    }

    public static boolean isSimplifiedChinese(String requestedLocale) {
        String locale = requestedLocale == null ? "" : requestedLocale.strip().toLowerCase(Locale.ROOT);
        return locale.equals("zh") || locale.equals("zh-cn") || locale.equals("zh-hans");
    }

    public LocalizedTaxonomy localizeDiscoveryCategories(List<String> categories, String requestedLocale) {
        List<String> source = categories == null ? List.of() : categories.stream()
                .map(value -> value == null ? "" : value.strip())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (!isSimplifiedChinese(requestedLocale)
                || source.isEmpty()
                || source.size() > MAX_DISCOVERY_CATEGORIES) {
            return new LocalizedTaxonomy(identity(source), false);
        }
        Request request = new Request(DISCOVERY_TAXONOMY_ID, "BGG discovery categories", "", source, List.of());
        try {
            return translations.translate(request)
                    .filter(translation -> validFor(request, translation))
                    .map(translation -> new LocalizedTaxonomy(indexed(source, translation.categories()), true))
                    .orElseGet(() -> new LocalizedTaxonomy(identity(source), false));
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG discovery category translation fell back to source values");
            return new LocalizedTaxonomy(identity(source), false);
        }
    }

    public LocalizedDiscoveryTaxonomy localizeDiscoveryTaxonomy(
            List<String> categories, List<String> mechanics, String requestedLocale) {
        List<String> sourceCategories = normalizedTaxonomy(categories);
        List<String> sourceMechanics = normalizedTaxonomy(mechanics);
        LocalizedDiscoveryTaxonomy fallback = new LocalizedDiscoveryTaxonomy(
                identity(sourceCategories), identity(sourceMechanics), false);
        if (!isSimplifiedChinese(requestedLocale)
                || sourceCategories.size() + sourceMechanics.size() > MAX_RANKED_CATALOG_TERMS
                || (sourceCategories.isEmpty() && sourceMechanics.isEmpty())) {
            return fallback;
        }
        try {
            Map<String, String> localizedCategories = new java.util.LinkedHashMap<>();
            Map<String, String> localizedMechanics = new java.util.LinkedHashMap<>();
            List<TaxonomyTerm> terms = java.util.stream.Stream.concat(
                            sourceCategories.stream().map(value -> new TaxonomyTerm(value, true)),
                            sourceMechanics.stream().map(value -> new TaxonomyTerm(value, false)))
                    .toList();
            for (int start = 0; start < terms.size(); start += TRANSLATION_CHUNK_SIZE) {
                List<TaxonomyTerm> chunk = terms.subList(start, Math.min(start + TRANSLATION_CHUNK_SIZE, terms.size()));
                List<String> categoryChunk = chunk.stream().filter(TaxonomyTerm::category).map(TaxonomyTerm::value).toList();
                List<String> mechanicChunk = chunk.stream().filter(term -> !term.category()).map(TaxonomyTerm::value).toList();
                Request request = new Request(
                        RANKED_CATALOG_TAXONOMY_ID,
                        "BGG ranked catalog taxonomy",
                        "",
                        categoryChunk,
                        mechanicChunk);
                Translation translation = translations.translate(request)
                        .filter(candidate -> validFor(request, candidate))
                        .orElse(null);
                if (translation == null) return fallback;
                localizedCategories.putAll(indexed(categoryChunk, translation.categories()));
                localizedMechanics.putAll(indexed(mechanicChunk, translation.mechanics()));
            }
            return new LocalizedDiscoveryTaxonomy(localizedCategories, localizedMechanics, true);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG ranked catalog taxonomy translation fell back to source values");
            return fallback;
        }
    }

    private List<String> normalizedTaxonomy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> value == null ? "" : value.strip())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, String> identity(List<String> source) {
        return source.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(value -> value, value -> value));
    }

    private Map<String, String> indexed(List<String> source, List<String> translated) {
        return IntStream.range(0, source.size()).boxed().collect(java.util.stream.Collectors.toUnmodifiableMap(
                source::get,
                index -> translated.get(index).strip()));
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

    public record LocalizedTaxonomy(Map<String, String> categories, boolean translated) {
        public LocalizedTaxonomy {
            categories = Map.copyOf(categories);
        }
    }

    public record LocalizedDiscoveryTaxonomy(
            Map<String, String> categories, Map<String, String> mechanics, boolean translated) {
        public LocalizedDiscoveryTaxonomy {
            categories = Map.copyOf(categories);
            mechanics = Map.copyOf(mechanics);
        }
    }

    private record TaxonomyTerm(String value, boolean category) {}
}
