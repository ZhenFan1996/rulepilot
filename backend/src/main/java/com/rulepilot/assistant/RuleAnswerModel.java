package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;

public interface RuleAnswerModel {

    default String providerId() {
        return "unspecified";
    }

    ModelDraft compose(ModelRequest request);

    record ModelRequest(String question, QuestionType questionType, AnswerContext context, List<EvidenceInput> evidence) {
        public ModelRequest {
            if (question == null || question.isBlank() || questionType == null || context == null
                    || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("answer model request is invalid");
            }
            evidence = List.copyOf(evidence);
        }
    }

    record AnswerContext(
            String currentLessonSection,
            String gamePhase,
            Integer playerCount,
            int activeExpansionCount,
            String previousQuestion) {

        public AnswerContext(
                String currentLessonSection,
                String gamePhase,
                Integer playerCount,
                int activeExpansionCount) {
            this(currentLessonSection, gamePhase, playerCount, activeExpansionCount, null);
        }

        public AnswerContext {
            if (playerCount != null && playerCount < 1 || activeExpansionCount < 0) {
                throw new IllegalArgumentException("answer context is invalid");
            }
            currentLessonSection = optional(currentLessonSection);
            gamePhase = optional(gamePhase);
            previousQuestion = optional(previousQuestion);
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value.strip();
        }

        public String playerCountForPrompt() {
            return playerCount == null ? "not provided" : playerCount.toString();
        }
    }

    record EvidenceInput(UUID chunkId, String sectionType, String heading, String excerpt, int pageFrom, int pageTo) {}

    record ModelDraft(
            boolean answerable,
            String insufficiencyReason,
            String shortVerdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            String confidence) {

        public ModelDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }

        public ModelDraft(
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence) {
            this(true, null, shortVerdict, explanation, citationIds, exceptions, confidence);
        }
    }
}
