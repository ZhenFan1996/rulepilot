package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.visualaid.VisualRegionCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

/** Adds an optional verified visual without changing the validated cited text that will be published with it. */
@Service
@Profile("!test")
public class VisualLessonEnricher {

    private final RulebookUnderstandingCatalog understanding;
    private final VisualSectionPrioritizer prioritizer;
    private final VisualLessonSectionEnricher sectionEnricher;
    private final Clock clock;
    private final Duration compatibilityWorkflowTimeout;

    @Autowired
    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionCatalog indexedRegions,
            @Qualifier("boundedVisualRegionLocator") VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            AgentExecutionControl execution,
            @Value("${rulepilot.visual.compatibility-workflow-timeout:PT10M}")
                    Duration compatibilityWorkflowTimeout) {
        this(
                understanding,
                pageImages,
                candidates,
                proposals,
                indexedRegions,
                locator,
                prioritizer,
                execution,
                compatibilityWorkflowTimeout,
                Clock.systemUTC());
    }

    VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            AgentExecutionControl execution,
            Duration compatibilityWorkflowTimeout,
            Clock clock) {
        this(
                understanding,
                pageImages,
                candidates,
                VisualRegionProposer.unavailable(),
                locator,
                prioritizer,
                execution,
                compatibilityWorkflowTimeout,
                clock);
    }

    VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            AgentExecutionControl execution,
            Duration compatibilityWorkflowTimeout,
            Clock clock) {
        this(
                understanding,
                pageImages,
                candidates,
                proposals,
                VisualRegionCatalog.empty(),
                locator,
                prioritizer,
                execution,
                compatibilityWorkflowTimeout,
                clock);
    }

    VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionProposer proposals,
            VisualRegionCatalog indexedRegions,
            VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer,
            AgentExecutionControl execution,
            Duration compatibilityWorkflowTimeout,
            Clock clock) {
        if (clock == null) throw new IllegalArgumentException("visual enrichment clock is required");
        this.understanding = understanding;
        this.prioritizer = prioritizer;
        this.clock = clock;
        this.compatibilityWorkflowTimeout = compatibilityWorkflowTimeout;
        VisualReaderCropPolicy cropPolicy = new VisualReaderCropPolicy();
        VisualLessonMergePolicy mergePolicy = new VisualLessonMergePolicy(cropPolicy);
        this.sectionEnricher = new VisualLessonSectionEnricher(
                mergePolicy,
                new VisualLessonStepLocator(
                        pageImages,
                        candidates,
                        proposals,
                        indexedRegions,
                        locator,
                        cropPolicy,
                        execution,
                        clock,
                        compatibilityWorkflowTimeout));
    }

    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator,
            VisualSectionPrioritizer prioritizer) {
        this(
                understanding,
                pageImages,
                candidates,
                VisualRegionProposer.unavailable(),
                locator,
                prioritizer,
                null,
                VisualLessonStepLocator.DEFAULT_COMPATIBILITY_WORKFLOW_TIMEOUT,
                Clock.systemUTC());
    }

    public VisualLessonEnricher(
            RulebookUnderstandingCatalog understanding,
            DocumentPageImages pageImages,
            VisualRegionCandidateSelector candidates,
            VisualRegionLocator locator) {
        this(understanding, pageImages, candidates, locator, new VisualSectionPrioritizer());
    }

    public IllustratedLesson enrich(UUID documentVersionId, IllustratedLesson lesson) {
        return enrich(documentVersionId, lesson, null);
    }

    static int estimatedTeachingRunModelCalls(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) return 0;
        long estimated = plan.sections().stream()
                .filter(TeachingPlan.PlannedSection::visualEvidenceRecommended)
                .mapToLong(section -> {
                    List<Integer> candidatePages = section.visualSourcePageNumbers().isEmpty()
                            ? section.sourcePageNumbers()
                            : section.visualSourcePageNumbers();
                    long pages = candidatePages.stream().distinct().count();
                    return Math.max(1L, (pages + DocumentPageImages.MAX_PAGES_PER_READ - 1L)
                            / DocumentPageImages.MAX_PAGES_PER_READ);
                })
                .sum();
        if (estimated > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("teaching visual workload is too large");
        }
        // This sizes the initial token/deadline envelope from the Agent's bounded visual-page windows. It is not a
        // call limit: complete Agent corrections continue while durable resources remain.
        return (int) estimated;
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
        Set<Integer> selectedPositions = prioritizer.positions(lesson.sections());
        List<VisualFocus> acceptedVisuals = lesson.sections().stream()
                .flatMap(section -> section.steps().stream())
                .flatMap(step -> step.visualFoci().stream())
                .collect(Collectors.toCollection(ArrayList::new));
        List<SectionResult> sectionResults = new ArrayList<>();
        List<LessonSection> currentSections = new ArrayList<>(lesson.sections());
        Instant compatibilityDeadline = clock.instant().plus(compatibilityWorkflowTimeout);
        VisualLessonStepLocator.ProposalToolCircuit proposalToolCircuit = sectionEnricher.beginProposalWorkflow();
        for (int sectionIndex = 0; sectionIndex < lesson.sections().size(); sectionIndex++) {
            LessonSection section = lesson.sections().get(sectionIndex);
            if (!selectedPositions.contains(section.position())) continue;
            VisualLessonSectionEnricher.Result enriched = sectionEnricher.enrich(
                    map,
                    documentVersionId,
                    section,
                    modelConfigurationOwner,
                    runId,
                    compatibilityDeadline,
                    proposalToolCircuit,
                    progress,
                    acceptedVisuals,
                    List.of());
            SectionResult sectionResult = sectionResult(enriched);
            sectionResults.add(sectionResult);
            currentSections.set(sectionIndex, sectionResult.section());
            if (sectionResult.outcome() != null) {
                SectionProgress update = new SectionProgress(section.position(), section.title(), sectionResult.outcome());
                progress.sectionFinished(update);
                if (sectionResult.outcome().outcome() == Outcome.ADDED
                        || sectionResult.outcome().outcome() == Outcome.ADDED_WITH_CLAIM_CONFLICT) {
                    progress.sectionUpdated(update, lessonWithSections(lesson, currentSections));
                }
            }
        }
        IllustratedLesson enriched = lessonWithSections(lesson, currentSections);
        return new EnrichmentResult(
                enriched,
                sectionResults.stream().map(SectionResult::outcome).filter(java.util.Objects::nonNull).toList());
    }

    /**
     * Runs the optional visual boundary for one newly validated chapter. The caller owns publication, so the returned
     * section can be stored in the same durable snapshot as its cited text instead of starting a second lesson-wide
     * workflow after every chapter has finished.
     */
    public SectionEnrichment enrichSection(
            UUID documentVersionId,
            TeachingPlan.PlannedSection planned,
            LessonSection section,
            List<LessonSection> alreadyPublished,
            String modelConfigurationOwner,
            UUID runId,
            VisualProgressListener progress) {
        if (documentVersionId == null || planned == null || section == null || alreadyPublished == null || progress == null) {
            throw new IllegalArgumentException("section visual enrichment input is invalid");
        }
        var map = understanding.understanding(documentVersionId);
        List<VisualFocus> acceptedVisuals = alreadyPublished.stream()
                .flatMap(published -> published.steps().stream())
                .flatMap(step -> step.visualFoci().stream())
                .collect(Collectors.toCollection(ArrayList::new));
        section.steps().stream()
                .flatMap(step -> step.visualFoci().stream())
                .forEach(acceptedVisuals::add);
        VisualLessonSectionEnricher.Result result = sectionEnricher.enrich(
                map,
                documentVersionId,
                section,
                modelConfigurationOwner,
                runId,
                clock.instant().plus(compatibilityWorkflowTimeout),
                sectionEnricher.beginProposalWorkflow(),
                progress,
                acceptedVisuals,
                planned.visualSourcePageNumbers());
        SectionResult reported = sectionResult(result);
        if (reported.outcome() != null) {
            progress.sectionFinished(new SectionProgress(
                    section.position(), section.title(), reported.outcome()));
        }
        return new SectionEnrichment(reported.section(), reported.outcome());
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
            case ADDED_WITH_CLAIM_CONFLICT -> "第 " + sectionPosition + " 节已加入无冲突图示；冲突截图已跳过，正文保持不变";
            case ALREADY_PRESENT -> "第 " + sectionPosition + " 节已有局部规则书截图，无需重复处理";
            case NO_CITED_CANDIDATE -> "第 " + sectionPosition + " 节没有可引用的图片候选；仅省略配图，已校验正文保持不变";
            case NO_PAGE_IMAGE -> "第 " + sectionPosition + " 节的候选页没有可用图片；仅省略配图，已校验正文保持不变";
            case LOCATOR_RETURNED_NONE -> "第 " + sectionPosition + " 节未找到可靠局部图示；仅省略配图，已校验正文保持不变";
            case MODEL_SEMANTIC_REJECTED -> "第 " + sectionPosition + " 节的局部图示未通过当前规则步骤校验；仅省略配图，已校验正文保持不变";
            case MODEL_UNAVAILABLE -> "第 " + sectionPosition + " 节没有可用的视觉模型；仅省略配图，已校验正文保持不变";
            case MODEL_EXPLICIT_NO_REGION -> "第 " + sectionPosition + " 节的视觉 Agent 选择了 NO_VISUAL；这是有效的局部结果，正文保持不变";
            case MODEL_MALFORMED_RESPONSE -> "第 " + sectionPosition + " 节的视觉 Agent 已收到完整错误与 JSON 约定，但纠正后再次出现相同错误、未取得进展；仅省略配图，已校验正文保持不变";
            case MODEL_UNSUPPORTED_SCOPE -> "第 " + sectionPosition + " 节的视觉 Agent 已收到完整候选与引用范围，但纠正后仍重复越界选择、未取得进展；仅省略配图，正文保持不变";
            case MODEL_INVALID_GEOMETRY -> "第 " + sectionPosition + " 节的候选图边界未通过校验；仅省略配图，已校验正文保持不变";
            case MODEL_TIMEOUT -> "第 " + sectionPosition + " 节的配图在有限时间内未完成；仅省略配图，已校验正文保持不变";
            case MODEL_INTERRUPTED -> "第 " + sectionPosition + " 节的配图工作被安全中断；仅省略配图，已校验正文保持不变";
            case MODEL_BUSY -> "第 " + sectionPosition + " 节的配图容量已满；仅省略配图，已校验正文保持不变";
            case MODEL_PROVIDER_FAILURE -> "第 " + sectionPosition + " 节的视觉服务调用失败；这不是 JSON 格式错误，仅省略本节配图，已校验正文保持不变";
            case CANDIDATE_PREPARATION_FAILED -> "第 " + sectionPosition + " 节的候选截图无法生成；仅省略配图，已校验正文保持不变";
            case REJECTED_TOO_SMALL -> "第 " + sectionPosition + " 节的截图太小，无法辅助理解；仅省略配图，已校验正文保持不变";
            case REJECTED_MISSING_OBSERVATION -> "第 " + sectionPosition + " 节的截图没有可核对的图中说明；仅省略配图，已校验正文保持不变";
            case REJECTED_NON_VISUAL -> "第 " + sectionPosition + " 节的候选只有文字或标题；仅省略配图，已校验正文保持不变";
            case REJECTED_OUTSIDE_CANDIDATE -> "第 " + sectionPosition + " 节的返回区域不在可引用范围内；仅省略配图，已校验正文保持不变";
            case REJECTED_UNKNOWN_EVIDENCE -> "第 " + sectionPosition + " 节的截图没有对应规则依据；仅省略配图，已校验正文保持不变";
            case REJECTED_DUPLICATE -> "第 " + sectionPosition + " 节的截图与前文高度重复；仅省略配图，已校验正文保持不变";
            case REJECTED_STEP_MISMATCH -> "第 " + sectionPosition + " 节的截图没有直接对应当前步骤；仅省略配图，已校验正文保持不变";
            case REJECTED_CLAIM_CONFLICT -> "第 " + sectionPosition + " 节的截图与已验证正文冲突，已跳过截图并保留正文";
        };
    }

    public record EnrichmentResult(IllustratedLesson lesson, List<SectionOutcome> outcomes) {
        public EnrichmentResult {
            if (lesson == null || outcomes == null) throw new IllegalArgumentException("visual enrichment result is invalid");
            outcomes = List.copyOf(outcomes);
        }
    }

    public record SectionEnrichment(LessonSection section, SectionOutcome outcome) {
        public SectionEnrichment {
            if (section == null || outcome == null) {
                throw new IllegalArgumentException("section visual enrichment result is invalid");
            }
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
            if (sectionPosition < 1 || outcome == null || summary == null || summary.isBlank()) {
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
        MODEL_SEMANTIC_REJECTED,
        MODEL_UNAVAILABLE,
        MODEL_EXPLICIT_NO_REGION,
        MODEL_MALFORMED_RESPONSE,
        MODEL_UNSUPPORTED_SCOPE,
        MODEL_INVALID_GEOMETRY,
        MODEL_TIMEOUT,
        MODEL_INTERRUPTED,
        MODEL_BUSY,
        MODEL_PROVIDER_FAILURE,
        CANDIDATE_PREPARATION_FAILED,
        REJECTED_TOO_SMALL,
        REJECTED_MISSING_OBSERVATION,
        REJECTED_NON_VISUAL,
        REJECTED_OUTSIDE_CANDIDATE,
        REJECTED_UNKNOWN_EVIDENCE,
        REJECTED_DUPLICATE,
        REJECTED_STEP_MISMATCH,
        REJECTED_CLAIM_CONFLICT
    }

    private record SectionResult(LessonSection section, SectionOutcome outcome) {}

}
