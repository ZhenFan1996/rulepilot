package com.rulepilot.teaching.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anonymous Q&A constrained to a published lesson and its cited rulebook pages. */
@Service
@Profile("!test")
public class PublicLessonQuestionService {

    private static final Pattern RELEVANCE_TOKENS = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9]{2,}");
    private static final Set<String> GENERIC_RELEVANCE_TERMS = Set.of(
            "规则", "规则书", "玩家", "游戏", "步骤", "这个", "什么", "怎么", "如何", "然后", "可以", "需要",
            "时候", "进行", "完成", "开始", "一个", "说明", "知道", "支持", "使用", "所有", "以及", "如果",
            "那么", "因此", "这里", "那里", "does", "what", "when", "where", "with", "then", "from", "this",
            "that", "your", "you", "the", "and", "for", "are", "can", "after", "before", "into", "about",
            "rule", "rules", "rulebook", "player", "players", "game", "step", "steps");
    private static final int MIN_DIRECT_PAGE_TOPIC_OVERLAP = 2;

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
        PlayerLocale language = PlayerLocale.fromRequest(request.language());
        String lessonContext = lesson.lesson().sections().stream()
                .filter(section -> request.sectionPosition() != null && section.position() == request.sectionPosition())
                .findFirst()
                .map(section -> String.join(" ", section.topicKey(), section.title(), String.join(" ", section.coverageTags())))
                .orElse(null);
        var creation = answers.answerForPublicReader(
                lesson.documentVersionId(),
                request.question().strip(),
                lessonContext,
                request.previousQuestion(),
                language);
        Set<Integer> citedPages = citedPages(creation.answer());
        return new PublicAnswer(
                creation.assistantRunId(),
                creation.answer(),
                visualAids(lesson, citedPages, creation.citedEvidenceIds(), request.question(), creation.answer(), language),
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
            String question,
            RuleAnswering.Answer answer,
            PlayerLocale language) {
        return relevantSteps(
                        lesson,
                        citedPages,
                        citedEvidenceIds,
                        question,
                        answer,
                        step -> step.visualFocus() != null,
                        true)
                .stream()
                .map(step -> new VisualAid(visibleFocus(step.visualFocus(), language), visibleStepLabel(step, language)))
                .toList();
    }

    private List<LessonStep> relevantSteps(
            PublicLessonReader.PublicLesson lesson,
            Set<Integer> citedPages,
            Set<UUID> citedEvidenceIds,
            String question,
            RuleAnswering.Answer answer,
            Predicate<LessonStep> kind,
            boolean permitsDirectPageTopicFallback) {
        Set<String> answerTerms = relevanceTerms(questionAndAnswerText(question, answer));
        List<RankedStep> candidates = lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(kind)
                .filter(step -> step.sourcePages().stream().anyMatch(citedPages::contains))
                .filter(step -> step.visualFocus() == null || citedPages.contains(step.visualFocus().pageNumber()))
                .map(step -> new RankedStep(step, relevanceScore(step, answerTerms)))
                .filter(candidate -> sharesCitedEvidence(candidate.step(), citedEvidenceIds)
                        || (permitsDirectPageTopicFallback
                                && candidate.relevance() >= MIN_DIRECT_PAGE_TOPIC_OVERLAP))
                .sorted((left, right) -> {
                    int score = Integer.compare(right.relevance(), left.relevance());
                    return score != 0 ? score : Integer.compare(left.step().position(), right.step().position());
                })
                .toList();
        List<LessonStep> directMatches = candidates.stream()
                .filter(candidate -> candidate.relevance() > 0)
                .map(RankedStep::step)
                .limit(1)
                .toList();
        return directMatches;
    }

    private String questionAndAnswerText(String question, RuleAnswering.Answer answer) {
        StringBuilder text = new StringBuilder(question == null ? "" : question);
        append(text, answer.shortVerdict());
        append(text, answer.explanation());
        for (String exception : answer.exceptions()) append(text, exception);
        for (RuleAnswering.Citation citation : answer.citations()) append(text, citation.heading());
        return text.toString();
    }

    private void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) text.append(' ').append(value);
    }

    private int relevanceScore(LessonStep step, Set<String> answerTerms) {
        String visualLabel = step.visualFocus() == null ? "" : step.visualFocus().label();
        return (int) relevanceTerms(step.heading() + " " + step.text() + " " + visualLabel).stream()
                .filter(answerTerms::contains)
                .count();
    }

    private Set<String> relevanceTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = RELEVANCE_TOKENS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (containsHan(token)) {
                for (int index = 0; index < token.length() - 1; index++) addTerm(terms, token.substring(index, index + 2));
            } else {
                addTerm(terms, token);
            }
        }
        return Set.copyOf(terms);
    }

    private boolean containsHan(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private void addTerm(Set<String> terms, String candidate) {
        if (!GENERIC_RELEVANCE_TERMS.contains(candidate)) terms.add(candidate);
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

    private record RankedStep(LessonStep step, int relevance) {}

    private void validate(QuestionRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().strip().length() > 800
                || request.sectionPosition() != null && request.sectionPosition() < 1
                || request.previousQuestion() != null && request.previousQuestion().strip().length() > 800) {
            throw new IllegalArgumentException("public lesson question is invalid");
        }
    }

    public record QuestionRequest(String question, Integer sectionPosition, String previousQuestion, String language) {
        public QuestionRequest(String question, Integer sectionPosition, String previousQuestion) {
            this(question, sectionPosition, previousQuestion, "zh-CN");
        }
    }

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
