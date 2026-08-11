package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Supplies a syntax-only fallback before structured semantic interpretation. */
@Service
public class DeterministicQuestionUnderstanding implements QuestionUnderstanding {

    private static final Pattern SPACE = Pattern.compile("\\s+");

    @Override
    public UnderstoodQuestion understand(String question, QuestionContext context) {
        if (question == null || question.isBlank() || context == null) {
            throw new IllegalArgumentException("question and context are required");
        }
        String original = SPACE.matcher(question.strip()).replaceAll(" ");
        return new UnderstoodQuestion(
                context.documentVersionId(),
                original,
                original,
                QuestionType.RULE_QUERY,
                List.of(),
                Set.of());
    }
}
