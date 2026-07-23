package com.rulepilot.teaching.application;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Claim;
import com.rulepilot.teaching.VisualRegionLocator.PageImage;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private final DocumentPageImages pageImages;
    private final VisualRulebookPageFacts visualPageFacts;
    private final VisualRegionCandidateSelector candidates;
    private final VisualRegionLocator locator;
    private final VisualSectionPrioritizer prioritizer;
    private final VisualReaderCropPolicy cropPolicy;
    private final VisualLessonMergePolicy mergePolicy;
    private final VisualStepRelevancePolicy stepRelevancePolicy;
    private final int maxSections;
    private final int maxVisualStepsPerSection;
    private final int requestParallelism;

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
            @Value("${rulepilot.visual.request-parallelism:1}") int requestParallelism) {
        this.understanding = understanding;
        this.pageImages = pageImages;
        this.visualPageFacts = visualPageFacts;
        this.candidates = candidates;
        this.locator = locator;
        this.prioritizer = prioritizer;
        this.cropPolicy = new VisualReaderCropPolicy();
        this.mergePolicy = new VisualLessonMergePolicy(cropPolicy);
        this.stepRelevancePolicy = new VisualStepRelevancePolicy();
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
        this.requestParallelism = requestParallelism;
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
            SectionResult enriched = enrichSection(map, documentVersionId, section, modelConfigurationOwner, progress);
            SectionResult distinct = keepDistinctVisuals(section, enriched, acceptedVisuals);
            sectionResults.add(distinct);
            currentSections.set(sectionIndex, distinct.section());
            if (distinct.outcome() != null) {
                SectionProgress update = new SectionProgress(section.position(), section.title(), distinct.outcome());
                progress.sectionFinished(update);
                if (distinct.outcome().outcome() == Outcome.ADDED) {
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

    private SectionResult enrichSection(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            String modelConfigurationOwner,
            VisualProgressListener progress) {
        int existingVisualSteps = (int) section.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL && !cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .count();
        if (existingVisualSteps >= maxVisualStepsPerSection) {
            return result(section, Outcome.ALREADY_PRESENT);
        }
        List<VisualRegionLocator.LocatedRegion> accepted = new ArrayList<>();
        Outcome rejected = null;
        int availableStepSlots = (int) section.steps().stream()
                .filter(step -> step.kind() != TeachingMove.VISUAL || cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .count();
        int limit = Math.min(maxVisualStepsPerSection - existingVisualSteps, availableStepSlots);
        List<LessonStep> targets = visualTargets(section, limit);
        if (targets.isEmpty()) return result(section, Outcome.NO_CITED_CANDIDATE);
        try (var executor = Executors.newFixedThreadPool(Math.min(requestParallelism, targets.size()))) {
            List<Future<StepLocation>> attempts = targets.stream()
                    .map(step -> executor.submit(() -> locateWithProgress(
                            understanding, documentVersionId, section, step, modelConfigurationOwner, progress)))
                    .toList();
            for (Future<StepLocation> attempt : attempts) {
                StepLocation location = awaitLocation(attempt);
                if (location.region() != null) accepted.add(location.region());
                else if (location.rejection() != null) rejected = location.rejection();
            }
        }
        if (accepted.isEmpty()) return result(section, rejected == null ? Outcome.LOCATOR_RETURNED_NONE : rejected);
        VisualLessonMergePolicy.MergedVisualSection merged = mergePolicy.mergeVisualIntoSupportedSteps(section, accepted);
        if (merged.addedCount() == 0) return result(section, Outcome.REJECTED_UNKNOWN_EVIDENCE);
        return new SectionResult(merged.section(), new SectionOutcome(
                section.position(), Outcome.ADDED, addedSummary(section.position(), merged.addedCount())));
    }

    /**
     * Independent steps can inspect different cited pages at the same time. The fixed-size executor is intentionally
     * tiny and matches the provider-facing visual executor, so this shortens a player's wait without accumulating an
     * unbounded paid-image queue or changing the exact-step validation contract.
     */
    private StepLocation locateWithProgress(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner,
            VisualProgressListener progress) {
        VisualTarget target = new VisualTarget(section.position(), section.title(), step.position(), step.heading());
        progress.targetStarted(target);
        StepLocation location = locateForStep(understanding, documentVersionId, section, step, modelConfigurationOwner);
        progress.targetFinished(target, location.region() == null
                ? location.rejection() == null ? Outcome.LOCATOR_RETURNED_NONE : location.rejection()
                : Outcome.ADDED);
        return location;
    }

    private StepLocation awaitLocation(Future<StepLocation> attempt) {
        try {
            return attempt.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("visual lesson enrichment was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("visual lesson enrichment failed", failed.getCause());
        }
    }

    /**
     * Vision is strongest when it is asked to ground one player action at a time. Passing every paragraph from a
     * section invites a model to attach a perfectly real diagram to the wrong neighbouring rule.
     */
    private List<LessonStep> visualTargets(LessonSection section, int limit) {
        return section.steps().stream()
                .filter(step -> step.kind() != TeachingMove.VISUAL || cropPolicy.needsTighterReaderCrop(step.visualFocus()))
                .filter(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty())
                .sorted(java.util.Comparator.comparingInt(this::visualAffinity).reversed()
                        .thenComparingInt(LessonStep::position))
                .limit(limit)
                .toList();
    }

    private int visualAffinity(LessonStep step) {
        String target = (step.heading() + " " + step.text()).toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String cue : List.of(
                "图标", "符号", "卡牌", "卡片", "玩家板", "棋盘", "网格", "地图", "轨道", "骰子", "资源",
                "令牌", "标记", "方块", "建筑", "放置", "建造", "布局", "计分", "分数", "示例", "组件",
                "icon", "symbol", "card", "board", "grid", "map", "track", "dice", "resource", "token",
                "marker", "building", "score", "example", "component")) {
            if (target.contains(cue)) score++;
        }
        return score;
    }

    private StepLocation locateForStep(
            com.rulepilot.ingestion.layout.RulebookUnderstanding understanding,
            UUID documentVersionId,
            LessonSection section,
            LessonStep step,
            String modelConfigurationOwner) {
        Set<Integer> citedPages = new LinkedHashSet<>(step.sourcePages());
        List<VisualRegionCandidateSelector.Candidate> selected = candidates.select(
                understanding, citedPages, terms(section, step), visualPageFacts.find(documentVersionId, citedPages));
        if (selected.isEmpty()) return StepLocation.rejected(Outcome.NO_CITED_CANDIDATE);
        List<Integer> candidatePageOrder = selected.stream().map(VisualRegionCandidateSelector.Candidate::pageNumber)
                .distinct()
                .toList();
        Map<Integer, DocumentPageImages.PageImage> availablePages = pageImages.read(
                        documentVersionId, new LinkedHashSet<>(candidatePageOrder))
                .stream()
                .collect(Collectors.toMap(
                        DocumentPageImages.PageImage::pageNumber, image -> image, (first, ignored) -> first));
        List<PageImage> pages = candidatePageOrder.stream()
                .map(availablePages::get)
                .filter(java.util.Objects::nonNull)
                .limit(2)
                .map(image -> new PageImage(image.pageNumber(), image.mediaType(), image.content()))
                .toList();
        if (pages.isEmpty()) return StepLocation.rejected(Outcome.NO_PAGE_IMAGE);
        Set<Integer> attachedPages = pages.stream().map(PageImage::pageNumber).collect(Collectors.toUnmodifiableSet());
        List<VisualRegionCandidateSelector.Candidate> attachedCandidates = selected.stream()
                .filter(candidate -> attachedPages.contains(candidate.pageNumber()))
                .toList();
        List<Claim> claims = claims(step);
        var guide = locator.locateGuideWithResult(new VisualRegionLocator.VisualLocationRequest(
                section.title() + " · " + step.heading(), claims, attachedCandidates, pages, modelConfigurationOwner));
        if (guide.regions().isEmpty()) return StepLocation.rejected(outcomeFor(guide.diagnostic()));
        Set<UUID> evidenceIds = claims.stream().map(Claim::evidenceId).collect(Collectors.toSet());
        Outcome rejected = null;
        for (VisualRegionLocator.LocatedRegion candidate : guide.regions()) {
            VisualRegionLocator.LocatedRegion region = candidate;
            if (cropPolicy.needsReaderViewport(region)) {
                if (!cropPolicy.canExpandIntoReaderViewport(region)) {
                    rejected = Outcome.REJECTED_TOO_SMALL;
                    continue;
                }
                region = cropPolicy.expandIntoReaderViewport(region);
            }
            Outcome rejection = rejectionFor(region, attachedCandidates, evidenceIds);
            if (rejection == null && !supportsExactStep(region, step)) {
                rejection = Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null && !stepRelevancePolicy.directlyIllustrates(step, region)) {
                rejection = Outcome.REJECTED_STEP_MISMATCH;
            }
            if (rejection == null) return StepLocation.accepted(region);
            rejected = rejection;
        }
        return StepLocation.rejected(rejected == null ? Outcome.REJECTED_UNKNOWN_EVIDENCE : rejected);
    }

    private boolean supportsExactStep(VisualRegionLocator.LocatedRegion region, LessonStep step) {
        return region.supportedStepPositions().isEmpty() || region.supportedStepPositions().contains(step.position());
    }

    private Outcome rejectionFor(
            VisualRegionLocator.LocatedRegion region,
            List<VisualRegionCandidateSelector.Candidate> attachedCandidates,
            Set<UUID> evidenceIds) {
        if (!cropPolicy.isCompactReaderCrop(region)) return Outcome.REJECTED_WHOLE_PAGE;
        if (!cropPolicy.isReadableForPlayer(region)) return Outcome.REJECTED_TOO_SMALL;
        if (region.visibleDescription().isBlank()) return Outcome.REJECTED_MISSING_OBSERVATION;
        if (!cropPolicy.isUsefulPlayerVisual(region)) return Outcome.REJECTED_NON_VISUAL;
        if (!cropPolicy.intersectsCandidate(region, attachedCandidates)) return Outcome.REJECTED_OUTSIDE_CANDIDATE;
        if (!evidenceIds.containsAll(region.supportedEvidenceIds())) return Outcome.REJECTED_UNKNOWN_EVIDENCE;
        return null;
    }

    private SectionResult result(LessonSection section, Outcome outcome) {
        return new SectionResult(section, new SectionOutcome(section.position(), outcome, outcomeSummary(section.position(), outcome)));
    }

    private String addedSummary(int sectionPosition, int count) {
        return count == 1
                ? outcomeSummary(sectionPosition, Outcome.ADDED)
                : "第 " + sectionPosition + " 节已加入 " + count + " 处可核对的局部规则书截图";
    }

    private Outcome outcomeFor(VisualRegionLocator.Diagnostic diagnostic) {
        return switch (diagnostic) {
            case NO_REGION -> Outcome.LOCATOR_RETURNED_NONE;
            case OVERSIZED_REGION -> Outcome.MODEL_OVERSIZED_REGION;
            case SEMANTIC_REJECTED -> Outcome.MODEL_SEMANTIC_REJECTED;
            case MODEL_UNAVAILABLE -> Outcome.MODEL_UNAVAILABLE;
            case EXPLICIT_NO_REGION -> Outcome.MODEL_EXPLICIT_NO_REGION;
            case MALFORMED_RESPONSE -> Outcome.MODEL_MALFORMED_RESPONSE;
            case UNSUPPORTED_SCOPE -> Outcome.MODEL_UNSUPPORTED_SCOPE;
            case INVALID_GEOMETRY -> Outcome.MODEL_INVALID_GEOMETRY;
            case NON_CHINESE_OBSERVATION -> Outcome.MODEL_NON_CHINESE_OBSERVATION;
            case TIMEOUT -> Outcome.MODEL_TIMEOUT;
            case INTERRUPTED -> Outcome.MODEL_INTERRUPTED;
            case EXECUTOR_BUSY -> Outcome.MODEL_BUSY;
            case PROVIDER_FAILURE -> Outcome.MODEL_PROVIDER_FAILURE;
            case FOUND -> throw new IllegalArgumentException("found visual location cannot be rejected");
        };
    }

    private String outcomeSummary(int sectionPosition, Outcome outcome) {
        return switch (outcome) {
            case ADDED -> "第 " + sectionPosition + " 节已加入可核对的局部规则书截图";
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

    private List<String> terms(LessonSection section, LessonStep step) {
        List<String> result = new ArrayList<>();
        result.add(section.title());
        result.addAll(section.coverageTags());
        result.add(step.heading());
        result.add(step.text());
        return List.copyOf(result);
    }

    private List<Claim> claims(LessonStep step) {
        return new LinkedHashSet<>(step.sourceChunkIds()).stream()
                .map(id -> new Claim(id, claimText(step), step.sourcePages(), step.position()))
                .toList();
    }

    /** The heading gives vision an unambiguous target when adjacent steps cite the same prose chunk. */
    private String claimText(LessonStep step) {
        String prefix = "步骤 " + step.position() + "（" + step.heading() + "）：";
        int remaining = 600 - prefix.length();
        if (remaining <= 0) return prefix.substring(0, 600);
        String body = step.text();
        return body.length() <= remaining ? prefix + body : prefix + body.substring(0, remaining);
    }

    /**
     * A crop is a reading aid, not decorative repetition. Keep the first grounded use of a substantially identical
     * viewport and leave later steps as their original rule prose so a player does not see the same diagram twice.
     */
    private SectionResult keepDistinctVisuals(
            LessonSection original,
            SectionResult candidate,
            List<VisualFocus> acceptedVisuals) {
        if (candidate.outcome() == null || candidate.outcome().outcome() != Outcome.ADDED) return candidate;
        VisualLessonMergePolicy.DistinctVisualSection distinct =
                mergePolicy.keepDistinctVisuals(original, candidate.section(), acceptedVisuals);
        if (!distinct.hadDuplicate()) return candidate;
        if (distinct.addedCount() == 0) return result(original, Outcome.REJECTED_DUPLICATE);
        return new SectionResult(distinct.section(), new SectionOutcome(
                original.position(), Outcome.ADDED, addedSummary(original.position(), distinct.addedCount())));
    }

    private record StepLocation(VisualRegionLocator.LocatedRegion region, Outcome rejection) {
        static StepLocation accepted(VisualRegionLocator.LocatedRegion region) {
            return new StepLocation(region, null);
        }

        static StepLocation rejected(Outcome rejection) {
            return new StepLocation(null, rejection);
        }
    }
}
