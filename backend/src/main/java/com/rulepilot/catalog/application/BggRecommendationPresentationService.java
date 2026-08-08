package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggRecommendationPresentation;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class BggRecommendationPresentationService implements BggRecommendationPresentation {

    private final BggMetadataLocalizationService localization;

    BggRecommendationPresentationService(BggMetadataLocalizationService localization) {
        this.localization = localization;
    }

    @Override
    public boolean usesSimplifiedChinese(String locale) {
        return BggMetadataLocalizationService.isSimplifiedChinese(locale);
    }

    @Override
    public String normalizeSourceName(String sourceName) {
        return SimplifiedChineseText.normalize(sourceName);
    }

    @Override
    public LocalizedTaxonomy localizeTaxonomy(
            List<String> categories, List<String> mechanics, String locale) {
        BggMetadataLocalizationService.LocalizedDiscoveryTaxonomy taxonomy =
                localization.localizeDiscoveryTaxonomy(categories, mechanics, locale);
        return new LocalizedTaxonomy(taxonomy.categories(), taxonomy.mechanics());
    }
}
