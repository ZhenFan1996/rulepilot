package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.List;
import java.util.UUID;

public interface RuleStructureRepository {

    void replace(UUID documentVersionId, List<DetectedRuleSection> sections);

    List<DetectedRuleSection> findByDocumentVersion(UUID documentVersionId);

    record DetectedRuleSection(
            LessonRuleSectionType type, String content, List<Integer> pageNumbers) {
        public DetectedRuleSection {
            pageNumbers = List.copyOf(pageNumbers);
        }
    }
}
