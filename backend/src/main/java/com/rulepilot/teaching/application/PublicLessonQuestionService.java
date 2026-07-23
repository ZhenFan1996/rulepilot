package com.rulepilot.teaching.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Optional<PublicAnswer> answer(UUID planId, QuestionRequest request) {
        validate(request);
        return lessons.find(planId).map(lesson -> answer(lesson, request));
    }

    private PublicAnswer answer(PublicLessonReader.PublicLesson lesson, QuestionRequest request) {
        String lessonContext = lesson.lesson().sections().stream()
                .filter(section -> request.sectionPosition() != null && section.position() == request.sectionPosition())
                .findFirst()
                .map(section -> String.join(" ", section.topicKey(), section.title(), String.join(" ", section.coverageTags())))
                .orElse(null);
        var creation = answers.answerForPublicReader(
                lesson.documentVersionId(), request.question().strip(), lessonContext, request.previousQuestion());
        Set<Integer> citedPages = citedPages(creation.answer());
        return new PublicAnswer(
                creation.assistantRunId(),
                creation.answer(),
                visualAids(lesson, citedPages, creation.citedEvidenceIds()),
                examples(lesson, citedPages, creation.citedEvidenceIds()));
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
            PublicLessonReader.PublicLesson lesson, Set<Integer> citedPages, Set<UUID> citedEvidenceIds) {
        return lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.visualFocus() != null)
                .filter(step -> citedPages.contains(step.visualFocus().pageNumber()))
                .filter(step -> sharesCitedEvidence(step, citedEvidenceIds))
                .map(step -> new VisualAid(step.visualFocus(), step.heading()))
                .distinct()
                .limit(2)
                .toList();
    }

    private List<Example> examples(
            PublicLessonReader.PublicLesson lesson, Set<Integer> citedPages, Set<UUID> citedEvidenceIds) {
        return lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.kind() == TeachingMove.EXAMPLE)
                .filter(step -> step.sourcePages().stream().anyMatch(citedPages::contains))
                .filter(step -> sharesCitedEvidence(step, citedEvidenceIds))
                .map(step -> new Example(step.heading(), step.text(), citedSourcePages(step, citedPages)))
                .distinct()
                .limit(2)
                .toList();
    }

    private boolean sharesCitedEvidence(LessonStep step, Set<UUID> citedEvidenceIds) {
        return !citedEvidenceIds.isEmpty() && step.sourceChunkIds().stream().anyMatch(citedEvidenceIds::contains);
    }

    private List<Integer> citedSourcePages(LessonStep step, Set<Integer> citedPages) {
        return step.sourcePages().stream().filter(citedPages::contains).toList();
    }

    private void validate(QuestionRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().strip().length() > 800
                || request.sectionPosition() != null && request.sectionPosition() < 1
                || request.previousQuestion() != null && request.previousQuestion().strip().length() > 800) {
            throw new IllegalArgumentException("public lesson question is invalid");
        }
    }

    public record QuestionRequest(String question, Integer sectionPosition, String previousQuestion) {}

    public record PublicAnswer(
            UUID assistantRunId,
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
