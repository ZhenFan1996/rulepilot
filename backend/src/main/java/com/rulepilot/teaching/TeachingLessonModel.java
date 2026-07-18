package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.util.List;
import java.util.UUID;

public interface TeachingLessonModel {

    default String providerId() {
        return "unspecified";
    }

    SectionDraft compose(SectionRequest request);

    record SectionRequest(
            TeachingSectionType sectionType,
            int playerCount,
            int beginnerCount,
            int totalDurationMinutes,
            int sectionDurationSeconds,
            int maxSteps,
            List<EvidenceInput> evidence) {
        public SectionRequest {
            if (sectionType == null || playerCount < 1 || beginnerCount < 0 || totalDurationMinutes < 1
                    || sectionDurationSeconds < 10 || sectionDurationSeconds > totalDurationMinutes * 60
                    || maxSteps < 1 || maxSteps > 6
                    || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            evidence = List.copyOf(evidence);
        }
    }

    record EvidenceInput(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}

    record SectionDraft(
            String title,
            VisualKind visualKind,
            String visualCaption,
            List<StepDraft> steps) {
        public SectionDraft {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record StepDraft(String text, List<UUID> citationIds) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}
