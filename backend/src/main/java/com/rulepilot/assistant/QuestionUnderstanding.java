package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.assistant.domain.LearningIntent;
import java.util.List;
import java.util.UUID;

public interface QuestionUnderstanding {

    UnderstoodQuestion understand(String question, QuestionContext context);

    record QuestionContext(
            UUID documentVersionId,
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage,
            PriorTurnReference priorTurnReference) {

        public QuestionContext(UUID documentVersionId) {
            this(documentVersionId, null, null, PlayerLocale.ZH_CN, null);
        }

        public QuestionContext(
                UUID documentVersionId,
                String previousQuestion,
                LearningIntent learningIntent,
                PlayerLocale outputLanguage) {
            this(documentVersionId, previousQuestion, learningIntent, outputLanguage, null);
        }

        public QuestionContext withLearningIntent(LearningIntent resolvedLearningIntent) {
            if (learningIntent == resolvedLearningIntent) return this;
            return new QuestionContext(
                    documentVersionId,
                    previousQuestion,
                    resolvedLearningIntent,
                    outputLanguage,
                    priorTurnReference);
        }

        public QuestionContext withOutputLanguage(PlayerLocale resolvedOutputLanguage) {
            if (outputLanguage == resolvedOutputLanguage) return this;
            return new QuestionContext(
                    documentVersionId,
                    previousQuestion,
                    learningIntent,
                    resolvedOutputLanguage,
                    priorTurnReference);
        }

        public QuestionContext {
            if (documentVersionId == null) {
                throw new IllegalArgumentException("question context is invalid");
            }
            previousQuestion = normalize(previousQuestion);
            if (previousQuestion != null && previousQuestion.length() > 800) {
                throw new IllegalArgumentException("previous question is too long");
            }
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
            if (priorTurnReference != null
                    && !documentVersionId.equals(priorTurnReference.documentVersionId())) {
                throw new IllegalArgumentException("prior turn uses a different document version");
            }
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    /** Bounded provenance hint for reference resolution; it is never current-turn rule evidence. */
    record PriorTurnReference(
            UUID documentVersionId,
            String question,
            String groundedVerdict,
            List<PriorCitationReference> citations) {
        public PriorTurnReference {
            if (documentVersionId == null || blank(question) || question.length() > 800
                    || blank(groundedVerdict) || groundedVerdict.length() > 800
                    || citations == null || citations.size() > 8) {
                throw new IllegalArgumentException("prior turn reference is invalid");
            }
            question = question.strip();
            groundedVerdict = groundedVerdict.strip();
            citations = List.copyOf(citations);
            if (citations.stream().anyMatch(citation -> !documentVersionId.equals(citation.documentVersionId()))) {
                throw new IllegalArgumentException("prior citation uses a different document version");
            }
        }
    }

    record PriorCitationReference(UUID chunkId, UUID documentVersionId, int pageFrom, int pageTo) {
        public PriorCitationReference {
            if (chunkId == null || documentVersionId == null || pageFrom < 1 || pageTo < pageFrom) {
                throw new IllegalArgumentException("prior citation reference is invalid");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
