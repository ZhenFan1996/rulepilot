package com.rulepilot.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public cross-module boundary for grounded, anonymous rulebook answers. */
public interface RuleAnswering {

    AnswerResult answerForPublicReader(
            UUID documentVersionId, String question, String currentLessonSection, String previousQuestion);

    default AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String currentLessonSection,
            String previousQuestion,
            PlayerLocale outputLanguage) {
        return answerForPublicReader(documentVersionId, question, currentLessonSection, previousQuestion);
    }

    /**
     * The evidence identities stay inside the backend module boundary. They let a caller attach a lesson crop only
     * when that crop supports the exact evidence cited by the answer; controllers still expose only readable pages.
     */
    record AnswerResult(UUID assistantRunId, Answer answer, Set<UUID> citedEvidenceIds) {
        public AnswerResult {
            citedEvidenceIds = citedEvidenceIds == null ? Set.of() : Set.copyOf(citedEvidenceIds);
        }

        public AnswerResult(UUID assistantRunId, Answer answer) {
            this(assistantRunId, answer, Set.of());
        }
    }

    record Answer(
            String status,
            String shortVerdict,
            String explanation,
            List<Citation> citations,
            List<String> exceptions,
            String confidence,
            String answerBasis,
            String clarification,
            List<Warning> warnings) {
        public Answer {
            citations = List.copyOf(citations);
            exceptions = List.copyOf(exceptions);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification) {
            this(
                    status,
                    shortVerdict,
                    explanation,
                    citations,
                    exceptions,
                    confidence,
                    answerBasis,
                    clarification,
                    List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String clarification) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, null, clarification, List.of());
        }
    }

    record Citation(String heading, int pageFrom, int pageTo) {}

    record Warning(String type) {}
}
