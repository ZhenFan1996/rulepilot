package com.rulepilot.assistant;

import java.util.List;
import java.util.UUID;

/** Public cross-module boundary for grounded, anonymous rulebook answers. */
public interface RuleAnswering {

    AnswerResult answerForPublicReader(
            UUID documentVersionId, String question, String currentLessonSection, String previousQuestion);

    record AnswerResult(UUID assistantRunId, Answer answer) {}

    record Answer(
            String status,
            String shortVerdict,
            String explanation,
            List<Citation> citations,
            List<String> exceptions,
            String confidence,
            String clarification) {
        public Answer {
            citations = List.copyOf(citations);
            exceptions = List.copyOf(exceptions);
        }
    }

    record Citation(String heading, int pageFrom, int pageTo) {}
}
