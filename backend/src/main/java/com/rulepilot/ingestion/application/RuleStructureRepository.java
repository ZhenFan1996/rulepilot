package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleStructureRepository {

    void replace(
            UUID documentVersionId,
            List<DetectedRuleChunk> chunks,
            RulebookUnderstanding understanding);

    Optional<RulebookUnderstanding> findUnderstanding(UUID documentVersionId);

    record DetectedRuleChunk(
            String sectionType,
            String heading,
            String content,
            int pageNumber,
            ContentKind contentKind) {

        public enum ContentKind {
            CANONICAL_TEXT,
            VISUAL_PLACEHOLDER
        }

        public DetectedRuleChunk(String sectionType, String heading, String content, int pageNumber) {
            this(sectionType, heading, content, pageNumber, ContentKind.CANONICAL_TEXT);
        }

        public DetectedRuleChunk {
            if (sectionType == null || sectionType.isBlank()
                    || heading == null || heading.isBlank()
                    || content == null || content.isBlank()
                    || pageNumber < 1 || contentKind == null) {
                throw new IllegalArgumentException("detected rule chunk is invalid");
            }
            sectionType = sectionType.strip();
            heading = heading.strip();
            content = content.strip();
        }
    }
}
