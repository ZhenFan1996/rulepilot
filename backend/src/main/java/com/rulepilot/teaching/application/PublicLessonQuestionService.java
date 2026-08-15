package com.rulepilot.teaching.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Anonymous Q&A constrained to a published lesson and its cited rulebook pages. */
@Service
@Profile("!test")
public class PublicLessonQuestionService {

    private final PublicLessonReader lessons;
    private final RuleAnswering answers;

    public PublicLessonQuestionService(PublicLessonReader lessons, RuleAnswering answers) {
        this.lessons = lessons;
        this.answers = answers;
    }

    public Optional<PublicAnswer> answer(UUID planId, QuestionRequest request) {
        validate(request);
        return lessons.find(planId).map(lesson -> answer(lesson, request));
    }

    private PublicAnswer answer(PublicLessonReader.PublicLesson lesson, QuestionRequest request) {
        PlayerLocale language = PlayerLocale.forQuestion(
                request.question(), PlayerLocale.fromRequest(request.language()));
        var creation = request.learningIntent() == null
                ? answers.answerForPublicReader(
                        lesson.documentVersionId(),
                        request.question().strip(),
                        request.previousQuestion(),
                        language)
                : answers.answerForPublicReader(
                        lesson.documentVersionId(),
                        request.question().strip(),
                        request.previousQuestion(),
                        language,
                        request.learningIntent());
        Set<Integer> citedPages = citedPages(creation.answer());
        return new PublicAnswer(
                creation.answer(),
                visualAids(lesson, citedPages, creation.citedEvidenceIds(), language),
                List.of());
    }

    private Set<Integer> citedPages(RuleAnswering.Answer answer) {
        Set<Integer> pages = new LinkedHashSet<>();
        for (RuleAnswering.Citation citation : answer.citations()) {
            for (int page = citation.pageFrom(); page <= citation.pageTo(); page++) {
                pages.add(page);
            }
        }
        return Set.copyOf(pages);
    }

    private List<VisualAid> visualAids(
            PublicLessonReader.PublicLesson lesson,
            Set<Integer> citedPages,
            Set<UUID> citedEvidenceIds,
            PlayerLocale language) {
        return relevantVisualSteps(lesson, citedPages, citedEvidenceIds)
                .stream()
                .map(step -> new VisualAid(visibleFocus(step.visualFocus(), language), visibleStepLabel(step, language)))
                .toList();
    }

    private List<LessonStep> relevantVisualSteps(
            PublicLessonReader.PublicLesson lesson,
            Set<Integer> citedPages,
            Set<UUID> citedEvidenceIds) {
        if (citedEvidenceIds.isEmpty()) return List.of();
        return lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.visualFocus() != null)
                .filter(step -> step.sourcePages().stream().anyMatch(citedPages::contains))
                .filter(step -> citedPages.contains(step.visualFocus().pageNumber()))
                .filter(step -> sharesCitedEvidence(step, citedEvidenceIds))
                .sorted(java.util.Comparator.comparingInt(LessonStep::position))
                .limit(1)
                .toList();
    }

    private VisualFocus visibleFocus(VisualFocus source, PlayerLocale language) {
        if (language != PlayerLocale.EN) return source;
        return new VisualFocus(
                source.pageNumber(),
                "Rulebook illustration",
                source.visibleDescription(),
                source.x(),
                source.y(),
                source.width(),
                source.height());
    }

    private String visibleStepLabel(LessonStep step, PlayerLocale language) {
        return language == PlayerLocale.EN ? "Cited rulebook illustration" : step.heading();
    }

    private boolean sharesCitedEvidence(LessonStep step, Set<UUID> citedEvidenceIds) {
        return !citedEvidenceIds.isEmpty() && step.sourceChunkIds().stream().anyMatch(citedEvidenceIds::contains);
    }

    private void validate(QuestionRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().strip().length() > 800
                || request.previousQuestion() != null && request.previousQuestion().strip().length() > 800) {
            throw new IllegalArgumentException("public lesson question is invalid");
        }
    }

    public record QuestionRequest(
            String question,
            String previousQuestion,
            String language,
            RuleAnswering.PublicLearningIntent learningIntent) {
        public QuestionRequest(String question, String previousQuestion) {
            this(question, previousQuestion, "zh-CN", null);
        }

        public QuestionRequest(String question, String previousQuestion, String language) {
            this(question, previousQuestion, language, null);
        }
    }

    public record PublicAnswer(
            RuleAnswering.Answer answer,
            List<VisualAid> visualAids,
            List<Example> examples) {
        public PublicAnswer {
            visualAids = List.copyOf(visualAids);
            examples = List.copyOf(examples);
        }
    }

    public record VisualAid(VisualFocus visualFocus, String relatedStep) {}

    public record Example(String heading, String text, List<Integer> sourcePages) {
        public Example {
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
