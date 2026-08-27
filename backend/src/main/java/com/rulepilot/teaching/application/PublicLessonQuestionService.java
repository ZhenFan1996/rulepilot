package com.rulepilot.teaching.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Anonymous Q&A constrained to a published lesson and its cited rulebook pages. */
@Service
@Profile("!test")
public class PublicLessonQuestionService {

    // Every public crop request opens its source image as well as materializing the projected region. Budgeting both
    // real pixel surfaces bounds anonymous work without turning a valid lesson into a fixed illustration count.
    private static final long MAX_PUBLIC_VISUAL_WORK_PIXELS = 64L * 1_000 * 1_000;
    private static final long MAX_PUBLIC_SOURCE_PAGE_PIXELS = 40L * 1_000 * 1_000;
    private static final long MAX_PUBLIC_PROJECTED_IMAGE_PIXELS = 16L * 1_000 * 1_000;
    private static final long MAX_PUBLIC_VISUAL_METADATA_BYTES = 512L * 1_024;
    private static final int NORMALIZED_PAGE_SIZE = 1_000;
    private static final int PUBLIC_CROP_CONTEXT_PADDING = 35;

    private final PublicLessonReader lessons;
    private final RuleAnswering answers;
    private final DocumentPageImages pageImages;

    public PublicLessonQuestionService(
            PublicLessonReader lessons,
            RuleAnswering answers,
            DocumentPageImages pageImages) {
        this.lessons = lessons;
        this.answers = answers;
        this.pageImages = pageImages;
    }

    public Optional<PublicAnswer> answer(UUID planId, QuestionRequest request) {
        validate(request);
        return lessons.find(planId).map(lesson -> answer(lesson, request));
    }

    private PublicAnswer answer(PublicLessonReader.PublicLesson lesson, QuestionRequest request) {
        PlayerLocale language = PlayerLocale.fromRequest(request.language());
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
        VisualAidSelection visuals = visualAids(
                lesson, citedPages, creation.citedEvidenceIds(), language);
        return new PublicAnswer(
                creation.answer(),
                visuals.visualAids(),
                List.of(),
                visuals.failures());
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

    private VisualAidSelection visualAids(
            PublicLessonReader.PublicLesson lesson,
            Set<Integer> citedPages,
            Set<UUID> citedEvidenceIds,
            PlayerLocale language) {
        if (citedEvidenceIds.isEmpty()) return VisualAidSelection.empty();
        List<OwnedVisual> ownedVisuals = lesson.lesson().sections().stream()
                .flatMap(section -> section.steps().stream())
                .filter(step -> step.sourcePages().stream().anyMatch(citedPages::contains))
                .filter(step -> sharesCitedEvidence(step, citedEvidenceIds))
                .flatMap(step -> step.visualFoci().stream().map(focus -> new OwnedVisual(step, focus)))
                .filter(owned -> citedPages.contains(owned.focus().pageNumber()))
                .sorted(Comparator.comparingInt(owned -> owned.step().position()))
                .toList();
        List<VisualAid> result = new ArrayList<>();
        Set<VisualCrop> seenCrops = new LinkedHashSet<>();
        Map<Integer, PageDimensions> dimensionsByPage = new HashMap<>();
        Set<Integer> unavailablePages = new HashSet<>();
        EnumSet<VisualAidFailure> failures = EnumSet.noneOf(VisualAidFailure.class);
        long cropWorkPixels = 0;
        long metadataBytes = 0;
        for (OwnedVisual owned : ownedVisuals) {
            VisualCrop crop = VisualCrop.from(owned.focus());
            if (!seenCrops.add(crop)) continue;
            Optional<PageDimensions> dimensions = pageDimensions(
                    lesson.documentVersionId(), crop.pageNumber(), dimensionsByPage, unavailablePages);
            if (dimensions.isEmpty()) {
                failures.add(VisualAidFailure.PAGE_IMAGE_UNAVAILABLE);
                continue;
            }
            VisualFocus focus = visibleFocus(owned.focus(), language);
            String relatedStep = visibleStepLabel(owned.step(), language);
            long nextCropWork = cropWorkPixels(focus, dimensions.orElseThrow());
            long nextMetadataBytes = estimatedMetadataBytes(focus, relatedStep);
            if (exceedsBudget(cropWorkPixels, nextCropWork, MAX_PUBLIC_VISUAL_WORK_PIXELS)
                    || exceedsBudget(metadataBytes, nextMetadataBytes, MAX_PUBLIC_VISUAL_METADATA_BYTES)) {
                failures.add(VisualAidFailure.RESOURCE_BUDGET_EXCEEDED);
                break;
            }
            cropWorkPixels += nextCropWork;
            metadataBytes += nextMetadataBytes;
            result.add(new VisualAid(focus, relatedStep));
        }
        return new VisualAidSelection(result, failures);
    }

    private Optional<PageDimensions> pageDimensions(
            UUID documentVersionId,
            int pageNumber,
            Map<Integer, PageDimensions> dimensionsByPage,
            Set<Integer> unavailablePages) {
        PageDimensions known = dimensionsByPage.get(pageNumber);
        if (known != null) return Optional.of(known);
        if (unavailablePages.contains(pageNumber)) return Optional.empty();
        try {
            Optional<PageDimensions> loaded = pageImages.read(documentVersionId, Set.of(pageNumber)).stream()
                    .filter(page -> page.pageNumber() == pageNumber)
                    .findFirst()
                    .map(page -> new PageDimensions(page.width(), page.height()));
            if (loaded.isPresent()) {
                dimensionsByPage.put(pageNumber, loaded.orElseThrow());
                return loaded;
            }
        } catch (RuntimeException unavailableOptionalVisual) {
            // Page imagery is optional enrichment. The already validated cited answer remains publishable below.
        }
        unavailablePages.add(pageNumber);
        return Optional.empty();
    }

    private long cropWorkPixels(VisualFocus focus, PageDimensions page) {
        long sourcePixels = (long) page.width() * page.height();
        long left = projectPixel(Math.max(0, focus.x() - PUBLIC_CROP_CONTEXT_PADDING), page.width());
        long top = projectPixel(Math.max(0, focus.y() - PUBLIC_CROP_CONTEXT_PADDING), page.height());
        long right = projectPixelCeiling(
                Math.min(NORMALIZED_PAGE_SIZE, focus.x() + focus.width() + PUBLIC_CROP_CONTEXT_PADDING),
                page.width());
        long bottom = projectPixelCeiling(
                Math.min(NORMALIZED_PAGE_SIZE, focus.y() + focus.height() + PUBLIC_CROP_CONTEXT_PADDING),
                page.height());
        long projectedWidth = right - left;
        long projectedHeight = bottom - top;
        long projectedPixels = projectedWidth * projectedHeight;
        if (sourcePixels > MAX_PUBLIC_SOURCE_PAGE_PIXELS
                || projectedPixels > MAX_PUBLIC_PROJECTED_IMAGE_PIXELS
                || projectedPixels > MAX_PUBLIC_VISUAL_WORK_PIXELS - sourcePixels) {
            return MAX_PUBLIC_VISUAL_WORK_PIXELS + 1;
        }
        return sourcePixels + projectedPixels;
    }

    private long projectPixel(int normalized, int imageSize) {
        return (long) normalized * imageSize / NORMALIZED_PAGE_SIZE;
    }

    private long projectPixelCeiling(int normalized, int imageSize) {
        return ((long) normalized * imageSize + NORMALIZED_PAGE_SIZE - 1) / NORMALIZED_PAGE_SIZE;
    }

    private boolean exceedsBudget(long used, long additional, long maximum) {
        return additional > maximum - used;
    }

    private long estimatedMetadataBytes(VisualFocus focus, String relatedStep) {
        return 64L
                + focus.label().getBytes(StandardCharsets.UTF_8).length
                + focus.visibleDescription().getBytes(StandardCharsets.UTF_8).length
                + relatedStep.getBytes(StandardCharsets.UTF_8).length;
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
            List<Example> examples,
            Set<VisualAidFailure> visualAidFailures) {
        public PublicAnswer(
                RuleAnswering.Answer answer,
                List<VisualAid> visualAids,
                List<Example> examples) {
            this(answer, visualAids, examples, Set.of());
        }

        public PublicAnswer {
            visualAids = List.copyOf(visualAids);
            examples = List.copyOf(examples);
            visualAidFailures = Set.copyOf(visualAidFailures);
        }
    }

    public record VisualAid(VisualFocus visualFocus, String relatedStep) {}

    public enum VisualAidFailure {
        PAGE_IMAGE_UNAVAILABLE,
        RESOURCE_BUDGET_EXCEEDED
    }

    private record OwnedVisual(LessonStep step, VisualFocus focus) {}

    private record PageDimensions(int width, int height) {}

    private record VisualAidSelection(
            List<VisualAid> visualAids,
            Set<VisualAidFailure> failures) {
        private VisualAidSelection {
            visualAids = List.copyOf(visualAids);
            failures = Set.copyOf(failures);
        }

        private static VisualAidSelection empty() {
            return new VisualAidSelection(List.of(), Set.of());
        }
    }

    private record VisualCrop(int pageNumber, int x, int y, int width, int height) {
        private static VisualCrop from(VisualFocus focus) {
            return new VisualCrop(
                    focus.pageNumber(), focus.x(), focus.y(), focus.width(), focus.height());
        }
    }

    public record Example(String heading, String text, List<Integer> sourcePages) {
        public Example {
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
