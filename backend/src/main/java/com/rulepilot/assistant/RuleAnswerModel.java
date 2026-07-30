package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;

public interface RuleAnswerModel {

    default String providerId() {
        return "unspecified";
    }

    ModelDraft compose(ModelRequest request);

    default ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
        return compose(request);
    }

    /**
     * Produces bounded search phrases only. The phrases are untrusted retrieval input, never rule evidence or an
     * answer, and may be ignored when the configured model cannot safely provide them.
     */
    default List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
        return List.of();
    }

    record ModelRequest(String question, QuestionType questionType, AnswerContext context, List<EvidenceInput> evidence) {
        public ModelRequest {
            if (question == null || question.isBlank() || questionType == null || context == null
                    || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("answer model request is invalid");
            }
            evidence = List.copyOf(evidence);
        }
    }

    record RetrievalQueryRequest(String question, String previousQuestion, String currentLessonSection) {
        public RetrievalQueryRequest {
            if (question == null || question.isBlank() || question.length() > 800) {
                throw new IllegalArgumentException("retrieval query request is invalid");
            }
            question = question.strip();
            previousQuestion = optional(previousQuestion);
            currentLessonSection = optional(currentLessonSection);
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value.strip();
        }
    }

    record AnswerContext(
            String currentLessonSection,
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage) {

        public AnswerContext {
            currentLessonSection = optional(currentLessonSection);
            previousQuestion = optional(previousQuestion);
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value.strip();
        }

        public String learningIntentForPrompt() {
            return learningIntent == null ? "GENERAL_QUESTION" : learningIntent.name();
        }

        public String outputLanguageForPrompt() {
            return outputLanguage.promptName();
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
            String confidence,
            String answerBasis) {

        public ModelDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
            answerBasis = answerable && (answerBasis == null || answerBasis.isBlank())
                    ? "DIRECT_RULE"
                    : answerBasis;
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence, null);
        }

        public ModelDraft(
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence) {
            this(true, null, shortVerdict, explanation, citationIds, exceptions, confidence, null);
        }
    }
}
