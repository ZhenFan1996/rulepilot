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
        var creation = answers.answerForPublicReader(
                lesson.documentVersionId(),
                request.question().strip(),
                request.previousQuestion(),
                language,
                request.learningIntent(),
                lesson.citedPages());
        if (!withinPublishedPages(creation.answer(), lesson.citedPages())) {
            return new PublicAnswer(withheldAnswer(language), List.of(), List.of());
        }
        Set<Integer> citedPages = citedPages(creation.answer());
        return new PublicAnswer(
                creation.answer(),
                visualAids(lesson, citedPages, creation.citedEvidenceIds(), language),
                List.of());
    }

    private boolean withinPublishedPages(RuleAnswering.Answer answer, Set<Integer> publishedPages) {
        return answer.citations().stream().allMatch(citation -> java.util.stream.IntStream
                .rangeClosed(citation.pageFrom(), citation.pageTo())
                .allMatch(publishedPages::contains));
    }

    private RuleAnswering.Answer withheldAnswer(PlayerLocale language) {
        boolean english = language == PlayerLocale.EN;
        return new RuleAnswering.Answer(
                "INSUFFICIENT_EVIDENCE",
                english ? "This answer needs a rulebook page that is not public in this lesson."
                        : "这条答疑需要引用当前讲解未公开的规则书页。",
                english ? "I did not publish an answer with an inaccessible citation. Open the cited lesson pages or try another question."
                        : "为避免给出打不开的引用，本次没有发布答案。可以先查看讲解已引用的页面，或换一个问题。",
                List.of(),
                List.of(),
                "LOW",
                null);
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
        if (citedEvidenceIds.isEmpty()) return List.of();
        return lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.sourcePages().stream().anyMatch(citedPages::contains))
                .filter(step -> sharesCitedEvidence(step, citedEvidenceIds))
                .flatMap(step -> step.visualFoci().stream().map(focus -> new OwnedVisual(step, focus)))
                .filter(owned -> citedPages.contains(owned.focus().pageNumber()))
                .sorted(java.util.Comparator.comparingInt(owned -> owned.step().position()))
                .map(owned -> new VisualAid(
                        visibleFocus(owned.focus(), language),
                        visibleStepLabel(owned.step(), language)))
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
                source.height(),
                source.sourceKind());
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

    private record OwnedVisual(LessonStep step, VisualFocus focus) {}

    public record Example(String heading, String text, List<Integer> sourcePages) {
        public Example {
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
