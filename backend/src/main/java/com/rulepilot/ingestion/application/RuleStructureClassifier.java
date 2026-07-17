package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.application.RuleStructureRepository.DetectedRuleSection;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RuleStructureClassifier {

    private static final int MAX_EXCERPT_LENGTH = 1_200;

    public List<DetectedRuleSection> classify(List<DocumentProcessing.ExtractedPage> pages) {
        Map<LessonRuleSectionType, List<String>> excerpts = new EnumMap<>(LessonRuleSectionType.class);
        Map<LessonRuleSectionType, Set<Integer>> pageNumbers = new EnumMap<>(LessonRuleSectionType.class);

        for (DocumentProcessing.ExtractedPage page : pages) {
            String normalized = page.text().toLowerCase(Locale.ROOT);
            for (LessonRuleSectionType type : LessonRuleSectionType.values()) {
                if (type.keywords().stream().anyMatch(normalized::contains)) {
                    excerpts.computeIfAbsent(type, ignored -> new ArrayList<>()).add(excerpt(page.text()));
                    pageNumbers.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(page.pageNumber());
                }
            }
        }

        return excerpts.entrySet().stream()
                .map(entry -> new DetectedRuleSection(
                        entry.getKey(),
                        String.join("\n\n", entry.getValue()),
                        List.copyOf(pageNumbers.get(entry.getKey()))))
                .toList();
    }

    private String excerpt(String text) {
        String compact = text.strip();
        return compact.length() <= MAX_EXCERPT_LENGTH ? compact : compact.substring(0, MAX_EXCERPT_LENGTH) + "…";
    }
}
