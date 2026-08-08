package com.rulepilot.catalog;

import java.util.List;
import java.util.Map;

/** Locale-safe BGG presentation capability for the independent recommendation module. */
public interface BggRecommendationPresentation {

    boolean usesSimplifiedChinese(String locale);

    String normalizeSourceName(String sourceName);

    LocalizedTaxonomy localizeTaxonomy(List<String> categories, List<String> mechanics, String locale);

    record LocalizedTaxonomy(Map<String, String> categories, Map<String, String> mechanics) {
        public LocalizedTaxonomy {
            categories = Map.copyOf(categories);
            mechanics = Map.copyOf(mechanics);
        }
    }
}
