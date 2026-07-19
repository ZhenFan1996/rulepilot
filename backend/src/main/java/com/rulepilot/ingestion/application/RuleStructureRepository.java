package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.List;
import java.util.UUID;

public interface RuleStructureRepository {

    void replace(
            UUID documentVersionId,
            List<DetectedRuleSection> sections,
            List<DetectedRuleChunk> chunks);

    List<DetectedRuleSection> findByDocumentVersion(UUID documentVersionId);

    record DetectedRuleSection(
            LessonRuleSectionType type, String content, List<Integer> pageNumbers) {
        public DetectedRuleSection {
            pageNumbers = List.copyOf(pageNumbers);
        }
    }

    record DetectedRuleChunk(
            String sectionType,
            String heading,
            String content,
            int pageNumber) {
        public DetectedRuleChunk {
            if (sectionType == null || sectionType.isBlank()
                    || heading == null || heading.isBlank()
                    || content == null || content.isBlank()
                    || pageNumber < 1) {
                throw new IllegalArgumentException("detected rule chunk is invalid");
            }
            sectionType = sectionType.strip();
            heading = heading.strip();
            content = content.strip();
        }
    }
}
