package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Adds a verified visual reference without changing an already published text lesson. */
@Service
@Profile("!test")
public class VisualLessonEnricher {

    private final RulebookUnderstandingCatalog understanding;
    private final VisualSectionPrioritizer prioritizer;
    private final VisualReaderCropPolicy cropPolicy;
    private final VisualLessonMergePolicy mergePolicy;
    private final VisualLessonSectionEnricher sectionEnricher;
    private final int maxSections;
    private final int maxVisualStepsPerSection;

    @Autowired
    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRulebookPageFacts visualPageFacts,
            VisualRegionCandidateSelector candidates,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            @Value("${rulepilot.visual.max-sections:12}") int maxSections,
            @Value("${rulepilot.visual.max-steps-per-section:6}") int maxVisualStepsPerSection,
            @Value("${rulepilot.visual.enrichment.request-parallelism:1}") int requestParallelism) {
        this.understanding = understanding;
        this.prioritizer = prioritizer;
        this.cropPolicy = new VisualReaderCropPolicy();
        this.mergePolicy = new VisualLessonMergePolicy(cropPolicy);
        if (maxSections < 1 || maxSections > 20) {
            throw new IllegalArgumentException("visual section limit must be between one and twenty");
        }
        if (maxVisualStepsPerSection < 1 || maxVisualStepsPerSection > 6) {
            throw new IllegalArgumentException("visual step limit must be between one and six");
        }
        if (requestParallelism < 1 || requestParallelism > 3) {
            throw new IllegalArgumentException("visual request parallelism must be between one and three");
        }
        this.maxSections = maxSections;
        this.maxVisualStepsPerSection = maxVisualStepsPerSection;
        this.sectionEnricher = new VisualLessonSectionEnricher(
                cropPolicy,
                mergePolicy,
                new VisualLessonStepLocator(
                        pageImages,
                        visualPageFacts,
                        candidates,
                        locator,
                        cropPolicy),
                maxVisualStepsPerSection,
                requestParallelism);
    }

    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator) {
        this(understanding, pageImages, VisualRulebookPageFacts.empty(), candidates, locator, new VisualSectionPrioritizer(), 12, 6, 1);
    }

    VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRulebookPageFacts visualPageFacts,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator) {
        this(understanding, pageImages, visualPageFacts, candidates, locator, new VisualSectionPrioritizer(), 12, 6, 1);
    }

    public IllustratedLesson enrich(UUID documentVersionId, IllustratedLesson lesson) {
        return enrich(documentVersionId, lesson, null);
    }

    boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return sectionEnricher.supportsVisualEvidence(modelConfigurationOwner);
    }

    public IllustratedLesson enrich(UUID documentVersionId, IllustratedLesson lesson, String modelConfigurationOwner) {
        return enrichWithReport(documentVersionId, lesson, modelConfigurationOwner).lesson();
    }

    /**
     * Returns bounded per-section observations so optional visual work can be inspected without changing the lesson
     * when a crop is unavailable.
     */
    public EnrichmentResult enrichWithReport(
            UUID documentVersionId, IllustratedLesson lesson, String modelConfigurationOwner) {
        return enrichWithReport(documentVersionId, lesson, modelConfigurationOwner, ignored -> {});
    }

    /**
     * Reports each completed visual section as soon as its bounded image work finishes. The base lesson stays intact
     * throughout, while callers can show a player which chapter is currently being checked instead of a silent wait.
     */
    public EnrichmentResult enrichWithReport(
            UUID documentVersionId,
            IllustratedLesson lesson,
            String modelConfigurationOwner,
            Consumer<SectionProgress> progress) {
        if (progress == null) throw new IllegalArgumentException("visual enrichment progress listener is required");
        return enrichWithProgress(documentVersionId, lesson, modelConfigurationOwner, new VisualProgressListener() {
            @Override
            public void sectionFinished(SectionProgress section) {
                progress.accept(section);
            }
        });
    }

    /** Emits the currently inspected rule step as well as completed-section results for the player-facing run log. */
    public EnrichmentResult enrichWithProgress(
            UUID documentVersionId,
            IllustratedLesson lesson,
            String modelConfigurationOwner,
            VisualProgressListener progress) {
        return enrichWithProgress(documentVersionId, lesson, modelConfigurationOwner, null, progress);
    }

    public EnrichmentResult enrichWithProgress(
            UUID documentVersionId,
            IllustratedLesson lesson,
            String modelConfigurationOwner,
            UUID runId,
            VisualProgressListener progress) {
        if (progress == null) throw new IllegalArgumentException("visual enrichment progress listener is required");
        var map = understanding.understanding(documentVersionId);
        IllustratedLesson readerReadyLesson = mergePolicy.discardOverlyBroadVisuals(lesson);
        Set<Integer> selectedPositions = prioritizer.positions(
                readerReadyLesson.sections(), maxSections, maxVisualStepsPerSection);
        List<VisualFocus> acceptedVisuals = readerReadyLesson.sections().stream()
                .flatMap(section -> section.steps().stream())
                .map(LessonStep::visualFocus)
                .filter(java.util.Objects::nonNull)
                .filter(focus -> !cropPolicy.needsTighterReaderCrop(focus))
                .collect(Collectors.toCollection(ArrayList::new));
        List<SectionResult> sectionResults = new ArrayList<>();
        List<LessonSection> currentSections = new ArrayList<>(readerReadyLesson.sections());
        for (int sectionIndex = 0; sectionIndex < readerReadyLesson.sections().size(); sectionIndex++) {
            LessonSection section = readerReadyLesson.sections().get(sectionIndex);
            if (!selectedPositions.contains(section.position())) continue;
            VisualLessonSectionEnricher.Result enriched = sectionEnricher.enrich(
                    map, documentVersionId, section, modelConfigurationOwner, runId, progress, acceptedVisuals);
            SectionResult sectionResult = sectionResult(enriched);
            sectionResults.add(sectionResult);
            currentSections.set(sectionIndex, sectionResult.section());
            if (sectionResult.outcome() != null) {
                SectionProgress update = new SectionProgress(section.position(), section.title(), sectionResult.outcome());
                progress.sectionFinished(update);
                if (sectionResult.outcome().outcome() == Outcome.ADDED
                        || sectionResult.outcome().outcome() == Outcome.ADDED_WITH_CLAIM_CONFLICT) {
                    progress.sectionUpdated(update, lessonWithSections(readerReadyLesson, currentSections));
                }
            }
        }
        IllustratedLesson enriched = lessonWithSections(readerReadyLesson, currentSections);
        return new EnrichmentResult(
                enriched,
                sectionResults.stream().map(SectionResult::outcome).filter(java.util.Objects::nonNull).toList());
    }

    private IllustratedLesson lessonWithSections(IllustratedLesson original, List<LessonSection> sections) {
        return new IllustratedLesson(
                original.id(), original.teachingPlanId(), original.status(), List.copyOf(sections),
                original.generatorVersion(), original.createdAt());
    }

    private SectionResult sectionResult(VisualLessonSectionEnricher.Result result) {
        String summary = result.outcome() == Outcome.ADDED
                ? addedSummary(result.section().position(), result.addedCount())
                : outcomeSummary(result.section().position(), result.outcome());
        return new SectionResult(
                result.section(),
                new SectionOutcome(result.section().position(), result.outcome(), summary));
    }

    private String addedSummary(int sectionPosition, int count) {
        return count == 1
                ? outcomeSummary(sectionPosition, Outcome.ADDED)
                : "第 " + sectionPosition + " 节已加入 " + count + " 处可核对的局部规则书截图";
    }

    private String outcomeSummary(int sectionPosition, Outcome outcome) {
        return switch (outcome) {
            case ADDED -> "第 " + sectionPosition + " 节已加入可核对的局部规则书截图";
            case ADDED_WITH_CLAIM_CONFLICT -> "第 " + sectionPosition + " 节的图中信息与讲解正文存在冲突，已保留内容并标记为待复核";
            case ALREADY_PRESENT -> "第 " + sectionPosition + " 节已有局部规则书截图，无需重复处理";
            case NO_CITED_CANDIDATE -> "第 " + sectionPosition + " 节没有可引用的图片候选区域";
            case NO_PAGE_IMAGE -> "第 " + sectionPosition + " 节的候选页没有可用图片";
            case LOCATOR_RETURNED_NONE -> "第 " + sectionPosition + " 节的视觉模型未找到可靠局部图示";
            case MODEL_OVERSIZED_REGION -> "第 " + sectionPosition + " 节的视觉模型只返回了整页或过大范围";
            case MODEL_SEMANTIC_REJECTED -> "第 " + sectionPosition + " 节的局部图示未通过当前规则步骤的二次核对";
            case MODEL_UNAVAILABLE -> "第 " + sectionPosition + " 节没有可用的视觉模型";
            case MODEL_EXPLICIT_NO_REGION -> "第 " + sectionPosition + " 节的视觉模型确认没有合适局部图示";
            case MODEL_MALFORMED_RESPONSE -> "第 " + sectionPosition + " 节的视觉模型没有返回可用坐标";
            case MODEL_UNSUPPORTED_SCOPE -> "第 " + sectionPosition + " 节的视觉模型引用了未提供的页面或依据";
            case MODEL_INVALID_GEOMETRY -> "第 " + sectionPosition + " 节的视觉模型返回了无效截图坐标";
            case MODEL_NON_CHINESE_OBSERVATION -> "第 " + sectionPosition + " 节的视觉模型没有给出可读的中文图中说明，已跳过";
            case MODEL_TIMEOUT -> "第 " + sectionPosition + " 节的视觉模型响应超时";
            case MODEL_INTERRUPTED -> "第 " + sectionPosition + " 节的视觉模型工作被安全中断";
            case MODEL_BUSY -> "第 " + sectionPosition + " 节的视觉模型正在处理其他任务";
            case MODEL_PROVIDER_FAILURE -> "第 " + sectionPosition + " 节的视觉模型调用失败，已保留正文";
            case REJECTED_WHOLE_PAGE -> "第 " + sectionPosition + " 节返回整页，未把它误作局部讲解";
            case REJECTED_TOO_SMALL -> "第 " + sectionPosition + " 节的截图太小，无法辅助玩家理解，已跳过";
            case REJECTED_MISSING_OBSERVATION -> "第 " + sectionPosition + " 节的截图没有可核对的图中说明，已跳过";
            case REJECTED_NON_VISUAL -> "第 " + sectionPosition + " 节返回的区域只有文字或标题，已跳过";
            case REJECTED_OUTSIDE_CANDIDATE -> "第 " + sectionPosition + " 节返回区域不在可引用范围内，已跳过";
            case REJECTED_UNKNOWN_EVIDENCE -> "第 " + sectionPosition + " 节的截图没有对应规则依据，已跳过";
            case REJECTED_DUPLICATE -> "第 " + sectionPosition + " 节的截图与前文高度重复，已保留原规则步骤";
            case REJECTED_STEP_MISMATCH -> "第 " + sectionPosition + " 节的截图没有直接对应当前步骤，已保留原规则步骤";
        };
    }

    public record EnrichmentResult(IllustratedLesson lesson, List<SectionOutcome> outcomes) {
        public EnrichmentResult {
            if (lesson == null || outcomes == null) throw new IllegalArgumentException("visual enrichment result is invalid");
            outcomes = List.copyOf(outcomes);
        }
    }

    public record SectionProgress(int sectionPosition, String sectionTitle, SectionOutcome outcome) {
        public SectionProgress {
            if (sectionPosition < 1 || sectionTitle == null || sectionTitle.isBlank() || outcome == null) {
                throw new IllegalArgumentException("visual section progress is invalid");
            }
            sectionTitle = sectionTitle.strip();
        }
    }

    public record VisualTarget(int sectionPosition, String sectionTitle, int stepPosition, String stepHeading) {
        public VisualTarget {
            if (sectionPosition < 1 || stepPosition < 1 || sectionTitle == null || sectionTitle.isBlank()
                    || stepHeading == null || stepHeading.isBlank()) {
                throw new IllegalArgumentException("visual target progress is invalid");
            }
            sectionTitle = sectionTitle.strip();
            stepHeading = stepHeading.strip();
        }
    }

    public interface VisualProgressListener {
        default void targetStarted(VisualTarget target) {}

        default void targetFinished(VisualTarget target, Outcome outcome) {}

        default void sectionFinished(SectionProgress section) {}

        /** A verified crop is safe to show immediately; later sections may still be processing. */
        default void sectionUpdated(SectionProgress section, IllustratedLesson lesson) {}
    }

    public record SectionOutcome(int sectionPosition, Outcome outcome, String summary) {
        public SectionOutcome {
            if (sectionPosition < 1 || outcome == null || summary == null || summary.isBlank() || summary.length() > 240) {
                throw new IllegalArgumentException("visual enrichment section outcome is invalid");
            }
            summary = summary.strip();
        }
    }

    public enum Outcome {
        ADDED,
        ADDED_WITH_CLAIM_CONFLICT,
        ALREADY_PRESENT,
        NO_CITED_CANDIDATE,
        NO_PAGE_IMAGE,
        LOCATOR_RETURNED_NONE,
        MODEL_OVERSIZED_REGION,
        MODEL_SEMANTIC_REJECTED,
        MODEL_UNAVAILABLE,
        MODEL_EXPLICIT_NO_REGION,
        MODEL_MALFORMED_RESPONSE,
        MODEL_UNSUPPORTED_SCOPE,
        MODEL_INVALID_GEOMETRY,
        MODEL_NON_CHINESE_OBSERVATION,
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_BUSY,
        MODEL_PROVIDER_FAILURE,
        REJECTED_WHOLE_PAGE,
        REJECTED_TOO_SMALL,
        REJECTED_MISSING_OBSERVATION,
        REJECTED_NON_VISUAL,
        REJECTED_OUTSIDE_CANDIDATE,
        REJECTED_UNKNOWN_EVIDENCE,
        REJECTED_DUPLICATE,
        REJECTED_STEP_MISMATCH
    }

    private record SectionResult(LessonSection section, SectionOutcome outcome) {}

}
