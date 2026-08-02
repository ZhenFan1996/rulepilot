package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.assistant.domain.LearningIntent;
import java.util.UUID;

public interface QuestionUnderstanding {

    UnderstoodQuestion understand(String question, QuestionContext context);

    record QuestionContext(
            UUID documentVersionId,
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage) {

        public QuestionContext(UUID documentVersionId) {
            this(documentVersionId, null, null, PlayerLocale.ZH_CN);
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
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
