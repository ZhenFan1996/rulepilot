package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GroundedTeachingAgent {

    private static final Logger log = LoggerFactory.getLogger(GroundedTeachingAgent.class);
    static final String GENERATOR_VERSION = "adaptive-teaching-v35-endgame-check-fidelity";
    private static final Set<String> REUSABLE_GENERATOR_VERSIONS =
            Set.of(GENERATOR_VERSION);
    private static final int MAX_EVIDENCE_PER_SECTION = 10;
    private static final int EVIDENCE_PER_INTENT = 3;
    private static final int MAX_DRAFT_REPAIR_ATTEMPTS = 3;
    /*
     * The cited base lesson is published section by section.  Whole-lesson review improves it,
     * but must not turn a usable first read into an unbounded rewrite job.
     */
    private static final int MAX_POST_PUBLICATION_REVIEW_PASSES = 1;
    private static final int MAX_POST_PUBLICATION_REVIEW_CORRECTIONS = 4;
    private static final int MAX_POST_PUBLICATION_SCOPE_CORRECTIONS = 2;
    private static final int MAX_REVIEW_UNCITED_EVIDENCE_PER_SECTION = 2;
    private static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
    private static final Pattern RETRIEVAL_QUERY_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}'’-]+");
    private static final Set<String> ENGLISH_RETRIEVAL_FILLER = Set.of(
            "a", "an", "and", "are", "do", "does", "for", "how", "is", "of", "the", "to", "what", "when",
            "with", "you", "your");
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final EvidenceVerifier evidenceVerifier;
    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final VisualRulebookPageFacts visualFacts;
    private final VisualRulebookPageCatalogModel visualCatalog;
    private final LessonDraftPresentationNormalizer presentationNormalizer = new LessonDraftPresentationNormalizer();
    private final int maxToolCalls;
    private final int baseSectionParallelism;

    @Autowired
    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookPageCatalogModel visualCatalog,
            @Value("${rulepilot.teaching.agent.max-tool-calls:72}") int maxToolCalls,
            @Value("${rulepilot.teaching.base-section-parallelism:3}") int baseSectionParallelism) {
        this.tools = tools;
        this.model = model;
        this.evidenceVerifier = evidenceVerifier;
        this.critic = critic;
        this.invocations = invocations;
        this.visualFacts = visualFacts;
        this.visualCatalog = visualCatalog;
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.baseSectionParallelism = Math.max(1, Math.min(6, baseSectionParallelism));
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            int maxToolCalls,
            int baseSectionParallelism) {
        this(
                tools,
                model,
                evidenceVerifier,
                critic,
                invocations,
                visualFacts,
                VisualRulebookPageCatalogModel.unavailable(),
                maxToolCalls,
                baseSectionParallelism);
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            int maxToolCalls,
            int baseSectionParallelism) {
        this(
                tools,
                model,
                evidenceVerifier,
                critic,
                invocations,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                maxToolCalls,
                baseSectionParallelism);
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            int maxToolCalls) {
        this(
                tools,
                model,
                evidenceVerifier,
                critic,
                invocations,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                maxToolCalls,
                3);
    }

    /**
     * Completes the compatibility workflow in one call. The production launcher uses
     * {@link #createBase(TeachingPlan, UUID, IllustratedLesson, Consumer)} so a player can read
     * source-cited chapters before optional visual work and whole-lesson review finish.
     */
    public IllustratedLesson create(TeachingPlan plan, UUID assistantRunId) {
        return create(plan, assistantRunId, null);
    }

    public IllustratedLesson create(
            TeachingPlan plan, UUID assistantRunId, IllustratedLesson previousLesson) {
        return create(plan, assistantRunId, previousLesson, ignored -> {});
    }

    public IllustratedLesson create(
            TeachingPlan plan,
            UUID assistantRunId,
            IllustratedLesson previousLesson,
            Consumer<IllustratedLesson> progressPublisher) {
        return createComplete(plan, assistantRunId, previousLesson, progressPublisher);
    }

    /**
     * Publishes the cited text lesson incrementally before optional visual enrichment begins.
     * Image-only rulebooks still use their cited pages as primary evidence instead of guessing
     * from placeholder text.
     */
    public IllustratedLesson createBase(
            TeachingPlan plan,
            UUID assistantRunId,
            IllustratedLesson previousLesson,
            Consumer<IllustratedLesson> progressPublisher) {
        if (progressPublisher == null) throw new IllegalArgumentException("lesson progress publisher is required");
        UUID lessonId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Map<String, LessonSection> reusable = reusableSections(plan, previousLesson);
        Map<Integer, TeachingPacingPolicy.SectionPacing> pacing = TeachingPacingPolicy.allocate(plan);
        int queriesPerTopic = Math.max(1, Math.min(6, maxToolCalls / plan.sections().size()));
        List<LessonSection> sections = new ArrayList<>();
        List<DraftCandidate> reviewCandidates = new ArrayList<>();
        TeachingPlan.PlannedSection first = plan.sections().getFirst();
        SectionOutcome firstOutcome = baseSection(
                plan,
                first,
                pacing.get(first.position()),
                List.of(),
                reusable,
                assistantRunId,
                queriesPerTopic);
        sections.add(firstOutcome.section());
        if (firstOutcome.reviewCandidate() != null) reviewCandidates.add(firstOutcome.reviewCandidate());
        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);

        List<TeachingPlan.PlannedSection> remaining = plan.sections().subList(1, plan.sections().size());
        if (!remaining.isEmpty()) {
            List<PriorSectionContext> sharedContext = continuityContext(sections);
            Map<Integer, SectionOutcome> completed = new LinkedHashMap<>();
            try (var executor = Executors.newFixedThreadPool(Math.min(baseSectionParallelism, remaining.size()))) {
                List<Future<SectionOutcome>> futures = remaining.stream()
                        .map(planned -> executor.submit(() -> baseSection(
                                plan,
                                planned,
                                pacing.get(planned.position()),
                                sharedContext,
                                reusable,
                                assistantRunId,
                                queriesPerTopic)))
                        .toList();
                for (Future<SectionOutcome> future : futures) {
                    SectionOutcome outcome = await(future);
                    completed.put(outcome.position(), outcome);
                    while (completed.containsKey(sections.size() + 1)) {
                        SectionOutcome contiguous = completed.remove(sections.size() + 1);
                        sections.add(contiguous.section());
                        if (contiguous.reviewCandidate() != null) {
                            reviewCandidates.add(contiguous.reviewCandidate());
                        }
                        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                    }
                }
            }
        }
        IllustratedLesson readableDraft = lesson(lessonId, plan, sections, createdAt);
        if (readableDraft.status() == LessonStatus.DRAFT_READY && !reviewCandidates.isEmpty()) {
            reviewPublishedLesson(
                    plan,
                    reviewCandidates,
                    sections,
                    assistantRunId,
                    () -> progressPublisher.accept(lesson(lessonId, plan, sections, createdAt)));
        }
        return lesson(lessonId, plan, sections, createdAt);
    }

    private SectionOutcome baseSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queriesPerTopic) {
        return generateSection(
                plan,
                planned,
                pacing,
                priorSections,
                reusableSections,
                assistantRunId,
                queriesPerTopic,
                planned.position() - 1,
                GenerationMode.PROGRESSIVE_BASE);
    }

    private SectionOutcome generateSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queryBudget,
            int sectionIndex,
            GenerationMode mode) {
        LessonSection reusable = reusableSections.get(planned.topicKey());
        if (reusable != null) {
            recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "REUSED_VERIFIED_SECTION");
            return new SectionOutcome(planned.position(), reusable, null, 0);
        }
        EvidenceResolution resolution = retrieveSectionEvidence(
                plan, planned, assistantRunId, queryBudget, mode.bindVisualPageEvidence());
        if (!resolution.verified()) {
            recordPublication(
                    assistantRunId,
                    planned,
                    ActivityOutcome.REJECTED,
                    resolution.state() == EvidenceState.EMPTY
                            ? mode.noEvidenceCategory()
                            : mode.invalidEvidenceCategory());
            return new SectionOutcome(planned.position(), insufficient(planned), null, resolution.toolCalls());
        }
        try {
            DraftCandidate composed = composeDraft(
                    plan,
                    planned,
                    pacing,
                    priorSections,
                    resolution.evidence(),
                    assistantRunId,
                    sectionIndex,
                    mode.includeVisualEvidence());
            recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, mode.publishedCategory());
            return new SectionOutcome(planned.position(), composed.section(), composed, resolution.toolCalls());
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidDraft) {
            log.warn("Teaching section {} was withheld: {}", planned.topicKey(), invalidDraft.getMessage());
            recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, mode.withheldCategory());
            return new SectionOutcome(planned.position(), insufficient(planned), null, resolution.toolCalls());
        }
    }

    private EvidenceResolution retrieveSectionEvidence(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            int queryBudget,
            boolean bindVisualPageEvidence) {
        Map<UUID, RuleEvidence> evidenceById = new LinkedHashMap<>();
        List<List<RuleEvidence>> evidenceByIntent = new ArrayList<>();
        boolean conflictingEvidence = false;
        int toolCalls = 0;
        for (String query : retrievalQueries(planned, queryBudget)) {
            toolCalls++;
            try {
                List<RuleEvidence> retrieved = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        operationName("searchRuleEvidence", planned.position()),
                        estimateTokens(query),
                        "Version-scoped rule evidence retrieved",
                        () -> retrieve(plan.documentVersionId(), planned.topicKey(), query),
                        this::evidenceTokens);
                evidenceByIntent.add(retrieved);
                for (RuleEvidence source : retrieved) {
                    RuleEvidence existing = evidenceById.putIfAbsent(source.chunkId(), source);
                    if (existing != null && !sameEvidence(existing, source)) {
                        conflictingEvidence = true;
                        break;
                    }
                }
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException retrievalFailure) {
                log.warn("Teaching evidence retrieval failed for topic {}: {}", planned.topicKey(), retrievalFailure.getMessage());
            }
            if (conflictingEvidence) break;
        }
        List<RuleEvidence> evidence = conflictingEvidence ? List.of() : balancedEvidence(evidenceByIntent);
        if (bindVisualPageEvidence) {
            evidence = visualPageBoundEvidence(plan, planned, evidence, assistantRunId);
        }
        if (evidence.isEmpty()) {
            return new EvidenceResolution(List.of(), toolCalls, EvidenceState.EMPTY);
        }
        boolean verified = evidenceVerifier.verify(new VerificationRequest(
                        plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                .verified();
        return verified
                ? new EvidenceResolution(evidence, toolCalls, EvidenceState.VERIFIED)
                : new EvidenceResolution(List.of(), toolCalls, EvidenceState.INVALID);
    }

    private SectionOutcome await(Future<SectionOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("base lesson generation was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("base lesson generation failed", failed.getCause());
        }
    }

    private IllustratedLesson createComplete(
            TeachingPlan plan,
            UUID assistantRunId,
            IllustratedLesson previousLesson,
            Consumer<IllustratedLesson> progressPublisher) {
        if (progressPublisher == null) throw new IllegalArgumentException("lesson progress publisher is required");
        UUID lessonId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        List<LessonSection> sections = new ArrayList<>();
        List<DraftCandidate> reviewCandidates = new ArrayList<>();
        Map<String, LessonSection> reusableSections = reusableSections(plan, previousLesson);
        Map<Integer, TeachingPacingPolicy.SectionPacing> pacing = TeachingPacingPolicy.allocate(plan);
        int toolCalls = 0;
        int queriesPerTopic = Math.max(1, Math.min(6, maxToolCalls / plan.sections().size()));
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            boolean reusable = reusableSections.containsKey(planned.topicKey());
            if (!reusable && toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before topic {}", planned.topicKey());
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "TOOL_BUDGET_EXHAUSTED");
                sections.add(insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }
            SectionOutcome outcome = generateSection(
                    plan,
                    planned,
                    pacing.get(planned.position()),
                    continuityContext(sections),
                    reusableSections,
                    assistantRunId,
                    Math.min(queriesPerTopic, maxToolCalls - toolCalls),
                    sections.size(),
                    GenerationMode.COMPATIBILITY_COMPLETE);
            toolCalls += outcome.retrievalToolCalls();
            sections.add(outcome.section());
            if (outcome.reviewCandidate() != null) reviewCandidates.add(outcome.reviewCandidate());
            publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
        }

        IllustratedLesson draftReady = lesson(lessonId, plan, sections, createdAt);
        if (draftReady.status() == LessonStatus.DRAFT_READY) {
            reviewPublishedLesson(
                    plan, reviewCandidates, sections, assistantRunId,
                    () -> progressPublisher.accept(lesson(lessonId, plan, sections, createdAt)));
        }

        return lesson(lessonId, plan, sections, createdAt);
    }

    private IllustratedLesson lesson(
            UUID lessonId,
            TeachingPlan plan,
            List<LessonSection> sections,
            Instant createdAt) {
        IllustratedLesson lesson = new IllustratedLesson(
                lessonId,
                plan.id(),
                lessonStatus(plan, sections),
                sections,
                GENERATOR_VERSION,
                createdAt);
        return lesson;
    }

    private LessonStatus lessonStatus(TeachingPlan plan, List<LessonSection> sections) {
        if (sections.size() < plan.sections().size()) return LessonStatus.INCOMPLETE;
        List<LessonSection> required = sections.stream().filter(LessonSection::required).toList();
        if (required.stream().anyMatch(section -> section.evidenceStatus() == EvidenceStatus.INSUFFICIENT_EVIDENCE)) {
            return LessonStatus.INCOMPLETE;
        }
        return required.stream().allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                ? LessonStatus.COMPLETE
                : LessonStatus.DRAFT_READY;
    }

    private void publishProgress(
            Consumer<IllustratedLesson> progressPublisher,
            UUID lessonId,
            TeachingPlan plan,
            List<LessonSection> sections,
            Instant createdAt) {
        progressPublisher.accept(new IllustratedLesson(
                lessonId,
                plan.id(),
                lessonStatus(plan, sections),
                sections,
                GENERATOR_VERSION,
                createdAt));
    }

    private Map<String, LessonSection> reusableSections(
            TeachingPlan plan, IllustratedLesson previousLesson) {
        if (previousLesson == null
                || !plan.id().equals(previousLesson.teachingPlanId())
                || !REUSABLE_GENERATOR_VERSIONS.contains(previousLesson.generatorVersion())) {
            return Map.of();
        }
        Set<String> currentTopics = plan.sections().stream()
                .map(TeachingPlan.PlannedSection::topicKey)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Boolean> visualRequirements = plan.sections().stream()
                .collect(Collectors.toUnmodifiableMap(
                        TeachingPlan.PlannedSection::topicKey,
                        TeachingPlan.PlannedSection::visualEvidenceRecommended));
        return previousLesson.sections().stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .filter(section -> currentTopics.contains(section.topicKey()))
                .filter(section -> !model.supportsVisualEvidence(plan.createdBy())
                        || !visualRequirements.getOrDefault(section.topicKey(), false)
                        || section.steps().stream().anyMatch(step ->
                                step.kind() == TeachingMove.VISUAL && step.visualFocus() != null))
                .collect(Collectors.toUnmodifiableMap(LessonSection::topicKey, Function.identity()));
    }

    private List<RuleEvidence> retrieve(UUID documentVersionId, String topicKey, String query) {
        return List.copyOf(tools.searchRuleEvidence(new SearchRuleEvidence(
                        documentVersionId,
                        focusedRetrievalQuery(query),
                        EVIDENCE_PER_INTENT,
                        Set.of(),
                        null,
                        true,
                        true)));
    }

    String focusedRetrievalQuery(String query) {
        String focused = Stream.of(RETRIEVAL_QUERY_SEPARATOR.split(query.strip()))
                .filter(token -> !token.isBlank())
                .filter(token -> !ENGLISH_RETRIEVAL_FILLER.contains(token.toLowerCase(java.util.Locale.ROOT)))
                .collect(Collectors.joining(" "));
        return focused.isBlank() ? query.strip() : focused;
    }

    private List<String> retrievalQueries(TeachingPlan.PlannedSection topic, int limit) {
        Stream<String> queries = Stream.concat(
                topic.retrievalQueries().stream(), objectiveQueries(topic.objective()).stream());
        return queries.map(String::strip).filter(query -> !query.isBlank()).distinct().limit(limit).toList();
    }

    private List<RuleEvidence> visualPageBoundEvidence(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> retrieved,
            UUID assistantRunId) {
        boolean visualPlaceholder = retrieved.stream().anyMatch(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()));
        if (!visualPlaceholder || planned.sourcePageNumbers().isEmpty()) return retrieved;
        try {
            List<RuleEvidence> pageEvidence = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    operationName("readRuleEvidencePages", planned.position()),
                    planned.sourcePageNumbers().size(),
                    "Planner-selected visual rulebook pages retrieved",
                    () -> tools.readRuleEvidencePages(
                            plan.documentVersionId(), new LinkedHashSet<>(planned.sourcePageNumbers()), true),
                    this::evidenceTokens);
            if (!pageEvidence.isEmpty()) {
                log.info(
                        "Teaching topic {} is bound to visual source pages {}",
                        planned.topicKey(), planned.sourcePageNumbers());
                List<RuleEvidence> enriched = enrichVisualPageFacts(plan.documentVersionId(), pageEvidence, List.of());
                if (!hasUncatalogedVisualPageEvidence(enriched) || !visualCatalog.available(plan.createdBy())) {
                    return enriched;
                }
                return enrichWithRequiredVisualPageFacts(
                        plan, planned, pageEvidence, enriched, assistantRunId);
            }
        } catch (RuntimeException failure) {
            log.warn("Visual page-bound evidence read failed for topic {}: {}", planned.topicKey(), failure.getMessage());
        }
        return retrieved;
    }

    private List<RuleEvidence> enrichWithRequiredVisualPageFacts(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> pageEvidence,
            List<RuleEvidence> enriched,
            UUID assistantRunId) {
        Map<Integer, AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        pageEvidence.stream()
                .filter(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()))
                .flatMap(source -> source.pageImages().stream())
                .forEach(image -> images.putIfAbsent(image.pageNumber(), image));
        List<VisualRulebookPageFacts.PageFact> interpreted = new ArrayList<>();
        for (AssistantReadTools.RulePageImage image : images.values()) {
            try {
                var request = new VisualRulebookPageCatalogModel.CatalogRequest(
                        List.of(new com.rulepilot.teaching.TeachingOutlineModel.PageImageInput(
                                image.pageNumber(), image.mediaType(), image.content())),
                        plan.createdBy(),
                        plan.gameTitle());
                var catalog = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        "inspectRequiredVisualPage|" + planned.position() + "|" + image.pageNumber(),
                        800,
                        "Required visual rulebook page interpreted for grounded teaching",
                        () -> visualCatalog.summarize(request),
                        result -> estimateTokens(result.toString()));
                interpreted.addAll(catalog.pages().stream()
                        .map(summary -> new VisualRulebookPageFacts.PageFact(
                                summary.pageNumber(),
                                summary.printedTerms(),
                                summary.factualSummary(),
                                summary.keywords(),
                                summary.visualAnchors()))
                        .toList());
            } catch (RuntimeException failure) {
                log.warn(
                        "Required visual page interpretation failed for topic {} page {}: {}",
                        planned.topicKey(),
                        image.pageNumber(),
                        failure.getMessage());
            }
        }
        if (interpreted.isEmpty()) return enriched;
        visualFacts.merge(plan.documentVersionId(), interpreted);
        log.info(
                "Teaching topic {} added on-demand visual facts for pages {}",
                planned.topicKey(),
                interpreted.stream().map(VisualRulebookPageFacts.PageFact::pageNumber).toList());
        return enrichVisualPageFacts(plan.documentVersionId(), pageEvidence, interpreted);
    }

    private boolean hasUncatalogedVisualPageEvidence(List<RuleEvidence> evidence) {
        return evidence.stream().anyMatch(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()));
    }

    private List<RuleEvidence> enrichVisualPageFacts(
            UUID documentVersionId,
            List<RuleEvidence> evidence,
            List<VisualRulebookPageFacts.PageFact> supplementalFacts) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toUnmodifiableSet());
        if (pages.isEmpty()) return evidence;
        Map<Integer, String> factsByPage = Stream.concat(
                        visualFacts.find(documentVersionId, pages).stream(), supplementalFacts.stream())
                .collect(Collectors.toUnmodifiableMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::evidenceText,
                        (existing, supplied) -> supplied));
        if (factsByPage.isEmpty()) return evidence;
        return evidence.stream()
                .map(source -> {
                    String facts = source.pageFrom() == source.pageTo() ? factsByPage.get(source.pageFrom()) : null;
                    if (facts == null || !VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt())) return source;
                    return new RuleEvidence(
                            source.chunkId(),
                            source.documentVersionId(),
                            source.sectionType(),
                            source.heading(),
                            facts,
                            source.pageFrom(),
                            source.pageTo(),
                            source.pageImages());
                })
                .toList();
    }

    private List<String> objectiveQueries(String objective) {
        int maxLength = 480;
        if (objective.length() <= maxLength) return List.of(objective);
        String head = objective.substring(0, maxLength);
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace > 0) head = head.substring(0, lastSpace);
        String tail = objective.substring(Math.max(0, objective.length() - maxLength));
        int firstSpace = tail.indexOf(' ');
        if (firstSpace >= 0) tail = tail.substring(firstSpace + 1);
        return List.of(head, tail);
    }

    private List<RuleEvidence> balancedEvidence(List<List<RuleEvidence>> evidenceByIntent) {
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        for (int rank = 0; merged.size() < MAX_EVIDENCE_PER_SECTION; rank++) {
            boolean candidateAtRank = false;
            for (List<RuleEvidence> intentEvidence : evidenceByIntent) {
                if (rank >= intentEvidence.size()) {
                    continue;
                }
                candidateAtRank = true;
                RuleEvidence candidate = intentEvidence.get(rank);
                merged.putIfAbsent(candidate.chunkId(), candidate);
                if (merged.size() == MAX_EVIDENCE_PER_SECTION) {
                    break;
                }
            }
            if (!candidateAtRank) {
                break;
            }
        }
        return List.copyOf(merged.values());
    }

    private DraftCandidate composeDraft(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            int sectionIndex,
            boolean includeVisualEvidence) {
        boolean requiresVisualGrounding = includeVisualEvidence || evidence.stream()
                .anyMatch(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()));
        List<TeachingLessonModel.PageImageInput> pageImages = requiresVisualGrounding
                ? selectedPageImages(planned, evidence, plan.createdBy())
                : List.of();
        TeachingLessonModel.SectionRequest modelRequest = new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                planned.coverageTags(),
                plan.playerCount(),
                plan.beginnerCount(),
                plan.durationMinutes(),
                pacing.durationSeconds(),
                pacing.maxSteps(),
                priorSections,
                modelEvidence(plan.documentVersionId(), evidence),
                pageImages,
                planned.retrievalQueries(),
                plan.createdBy(),
                chapterScope(plan, planned));
        SectionDraft draft;
        try {
            draft = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    operationName("composeTeachingSection", planned.position()),
                    estimateTokens(modelRequest.toString()),
                    "Teaching section model output received",
                    () -> model.compose(modelRequest),
                    result -> estimateTokens(result.toString()));
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException visualCompositionFailure) {
            if (!modelRequest.pageImages().isEmpty() && !hasOnlyVisualPageEvidence(evidence)) {
                log.warn(
                        "Visual teaching composition for topic {} is unavailable; continuing with cited text: {}",
                        planned.topicKey(),
                        visualCompositionFailure.getMessage());
                recordVisualTextFallback(assistantRunId, planned);
                return fallbackToTextDraft(
                        plan, planned, evidence, modelRequest, assistantRunId, sectionIndex, 0);
            }
            throw visualCompositionFailure;
        }
        draft = normalizeDraft(draft, modelRequest);
        int maxRepairAttempts = modelRequest.pageImages().isEmpty() ? MAX_DRAFT_REPAIR_ATTEMPTS : 1;
        for (int repair = 0; ; repair++) {
            try {
                LessonSection accepted = validatedSection(
                        plan, planned, evidence, modelRequest, draft, EvidenceStatus.CITED_DRAFT);
                recordValidation(
                        assistantRunId,
                        planned,
                        repair,
                        ActivityOutcome.SUCCEEDED,
                        "CITED_DRAFT_ACCEPTED");
                return new DraftCandidate(sectionIndex, planned, evidence, modelRequest, draft, accepted);
            } catch (IllegalArgumentException rejectedDraft) {
                recordValidation(
                        assistantRunId,
                        planned,
                        repair,
                        ActivityOutcome.REJECTED,
                        rejectionCategory(rejectedDraft));
                if (repair == maxRepairAttempts) {
                    if (!modelRequest.pageImages().isEmpty() && !hasOnlyVisualPageEvidence(evidence)) {
                        return fallbackToTextDraft(
                                plan,
                                planned,
                                evidence,
                                modelRequest,
                                assistantRunId,
                                sectionIndex,
                                repair + 1);
                    }
                    if (repair == maxRepairAttempts) throw rejectedDraft;
                }
                String diagnostic = rejectedDraft.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : rejectedDraft.getMessage();
                List<String> feedback = modelRequest.pageImages().isEmpty() || !isVisualLocalizationFailure(rejectedDraft)
                        ? List.of(diagnostic)
                        : List.of(
                                diagnostic,
                                "The attached page images are usable visual evidence. Keep the grounded text, but repair "
                                        + "one VISUAL step: cite an attached-page E-reference and return a compact "
                                        + "0-1000 focus rectangle that contains the icon, component group, board area, "
                                        + "flow, or worked state named in that step. Do not fall back to text-only.");
                log.info(
                        "Teaching topic {} structural repair {}/{}: {}",
                        planned.topicKey(),
                        repair + 1,
                        maxRepairAttempts,
                        feedback.getFirst());
                SectionDraft draftToRevise = draft;
                try {
                    draft = invocations.invoke(
                            assistantRunId,
                            ActivityType.MODEL,
                            operationName("reviseTeachingSection", planned.position()),
                            estimateTokens(modelRequest.toString()) + estimateTokens(draftToRevise.toString())
                                    + estimateTokens(feedback.toString()),
                            "Teaching section revised from validation feedback",
                            () -> model.revise(modelRequest, draftToRevise, feedback),
                            result -> estimateTokens(result.toString()));
                } catch (AgentExecutionStoppedException stopped) {
                    throw stopped;
                } catch (RuntimeException visualRepairFailure) {
                    if (!modelRequest.pageImages().isEmpty() && !hasOnlyVisualPageEvidence(evidence)) {
                        log.warn(
                                "Visual teaching repair for topic {} is unavailable; continuing with cited text: {}",
                                planned.topicKey(),
                                visualRepairFailure.getMessage());
                        recordVisualTextFallback(assistantRunId, planned);
                        return fallbackToTextDraft(
                                plan, planned, evidence, modelRequest, assistantRunId, sectionIndex, repair + 1);
                    }
                    throw visualRepairFailure;
                }
                draft = normalizeDraft(draft, modelRequest);
            }
        }
    }

    private DraftCandidate fallbackToTextDraft(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest visualRequest,
            UUID assistantRunId,
            int sectionIndex,
            int validationAttempt) {
        TeachingLessonModel.SectionRequest textOnlyRequest = withoutPageImages(visualRequest);
        SectionDraft textOnlyDraft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("fallbackToTextTeachingSection", planned.position()),
                estimateTokens(textOnlyRequest.toString()),
                "Visual teaching section recomposed as complete grounded text",
                () -> model.compose(textOnlyRequest),
                result -> estimateTokens(result.toString()));
        textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest);
        for (int repair = 0; ; repair++) {
            try {
                LessonSection accepted = validatedSection(
                        plan, planned, evidence, textOnlyRequest, textOnlyDraft, EvidenceStatus.CITED_DRAFT);
                recordValidation(
                        assistantRunId,
                        planned,
                        validationAttempt + repair,
                        ActivityOutcome.SUCCEEDED,
                        "TEXT_FALLBACK_ACCEPTED");
                return new DraftCandidate(
                        sectionIndex, planned, evidence, textOnlyRequest, textOnlyDraft, accepted);
            } catch (IllegalArgumentException rejectedFallback) {
                recordValidation(
                        assistantRunId,
                        planned,
                        validationAttempt + repair,
                        ActivityOutcome.REJECTED,
                        "TEXT_FALLBACK_" + rejectionCategory(rejectedFallback));
                if (repair == MAX_DRAFT_REPAIR_ATTEMPTS) throw rejectedFallback;
                List<String> repairFeedback = List.of(
                        "Keep this section text-only and preserve all grounded rule coverage. "
                                + (rejectedFallback.getMessage() == null
                                        ? "The previous fallback failed lesson validation."
                                        : rejectedFallback.getMessage()));
                SectionDraft draftToRevise = textOnlyDraft;
                textOnlyDraft = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        operationName("reviseTextTeachingSection", planned.position()),
                        estimateTokens(textOnlyRequest.toString()) + estimateTokens(draftToRevise.toString())
                                + estimateTokens(repairFeedback.toString()),
                        "Text fallback revised from validation feedback",
                        () -> model.revise(textOnlyRequest, draftToRevise, repairFeedback),
                        result -> estimateTokens(result.toString()));
                textOnlyDraft = normalizeDraft(textOnlyDraft, textOnlyRequest);
                textOnlyDraft = preserveTextOnlyPresentationMetadata(draftToRevise, textOnlyDraft);
            }
        }
    }

    static SectionDraft preserveTextOnlyPresentationMetadata(SectionDraft previous, SectionDraft revised) {
        if (previous == null || revised == null) return revised;
        String caption = revised.visualCaption();
        if (caption == null || caption.isBlank() || caption.length() > 240) {
            caption = previous.visualCaption();
        }
        List<UUID> citations = revised.visualCitationIds();
        if (citations == null || citations.isEmpty()) {
            citations = previous.visualCitationIds();
        }
        VisualKind visualKind = revised.visualKind() == null ? previous.visualKind() : revised.visualKind();
        if (java.util.Objects.equals(caption, revised.visualCaption())
                && java.util.Objects.equals(citations, revised.visualCitationIds())
                && visualKind == revised.visualKind()) {
            return revised;
        }
        return new SectionDraft(revised.title(), visualKind, caption, citations, revised.steps());
    }

    private boolean isVisualLocalizationFailure(IllegalArgumentException rejection) {
        return rejectionCategory(rejection).startsWith("VISUAL_");
    }

    private boolean hasOnlyVisualPageEvidence(List<RuleEvidence> evidence) {
        return !evidence.isEmpty()
                && evidence.stream().allMatch(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()));
    }

    private TeachingLessonModel.SectionRequest withoutPageImages(TeachingLessonModel.SectionRequest request) {
        return new TeachingLessonModel.SectionRequest(
                request.topicKey(),
                request.title(),
                request.objective(),
                request.coverageTags(),
                request.playerCount(),
                request.beginnerCount(),
                request.totalDurationMinutes(),
                request.sectionDurationSeconds(),
                request.maxSteps(),
                request.priorSections(),
                request.evidence(),
                List.of(),
                request.requiredRuleIntents(),
                request.modelConfigurationOwner(),
                request.chapterScope());
    }

    private SectionDraft normalizeDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        return presentationNormalizer.normalize(draft, request);
    }

    private List<TeachingLessonModel.PageImageInput> selectedPageImages(
            TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence, String modelConfigurationOwner) {
        boolean imageOnlyEvidence = evidence.stream()
                .anyMatch(source -> VISUAL_PAGE_PLACEHOLDER.equals(source.excerpt()));
        if ((!planned.visualEvidenceRecommended() && !imageOnlyEvidence)
                || !model.supportsVisualEvidence(modelConfigurationOwner)) return List.of();
        Map<Integer, com.rulepilot.assistant.AssistantReadTools.RulePageImage> images = new LinkedHashMap<>();
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        Map<Integer, Integer> firstEvidenceRank = new LinkedHashMap<>();
        Set<String> topicTerms = visualTopicTerms(planned);
        IntStream.range(0, evidence.size()).forEach(index -> {
            RuleEvidence source = evidence.get(index);
            int sourceScore = 100 + visualTopicScore(source, topicTerms)
                    + (source.pageFrom() == source.pageTo() ? 20 : 0);
            source.pageImages().stream()
                    .filter(image -> image.pageNumber() >= source.pageFrom()
                            && image.pageNumber() <= source.pageTo())
                    .forEach(image -> {
                        images.putIfAbsent(image.pageNumber(), image);
                        scores.merge(image.pageNumber(), sourceScore, Integer::max);
                        firstEvidenceRank.putIfAbsent(image.pageNumber(), index);
                    });
        });
        List<TeachingLessonModel.PageImageInput> selected = images.keySet().stream()
                .sorted(Comparator
                        .<Integer>comparingInt(page -> scores.getOrDefault(page, 0))
                        .reversed()
                        .thenComparingInt(page -> firstEvidenceRank.getOrDefault(page, Integer.MAX_VALUE))
                        .thenComparingInt(Integer::intValue))
                .limit(2)
                .map(images::get)
                .map(image -> new TeachingLessonModel.PageImageInput(
                        image.pageNumber(), image.mediaType(), image.content(), image.width(), image.height()))
                .toList();
        if (!selected.isEmpty()) {
            log.info(
                    "Teaching topic {} selected visual evidence pages {}",
                    planned.topicKey(),
                    selected.stream().map(TeachingLessonModel.PageImageInput::pageNumber).toList());
        }
        return selected;
    }

    private Set<String> visualTopicTerms(TeachingPlan.PlannedSection planned) {
        return Stream.concat(
                        Stream.of(planned.topicKey(), planned.title()),
                        Stream.concat(planned.coverageTags().stream(), planned.retrievalQueries().stream()))
                .flatMap(value -> Stream.of(value.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")))
                .filter(term -> term.length() >= 3)
                .collect(Collectors.toUnmodifiableSet());
    }

    private int visualTopicScore(RuleEvidence source, Set<String> topicTerms) {
        String sourceIdentity = (source.sectionType() + " " + source.heading())
                .toLowerCase(java.util.Locale.ROOT);
        return (int) topicTerms.stream().filter(sourceIdentity::contains).limit(5).count() * 20;
    }

    private LessonSection validatedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft,
            EvidenceStatus evidenceStatus) {
        LessonDraftValidator.validateDraft(draft, modelRequest);

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        LessonDraftValidator.validateVisualBlockEvidence(draft, modelRequest, allowedEvidence);
        List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence);
        List<Claim> reviewClaims = LessonDraftValidator.reviewClaims(draft, visualCitationIds);
        List<EvidenceClaim> generatedClaims = reviewClaims.stream()
                .map(claim -> new EvidenceClaim(claim.text(), claim.citationIds()))
                .toList();
        var verification = evidenceVerifier.verify(new VerificationRequest(
                plan.documentVersionId(),
                evidence.stream().map(this::toVerifierEvidence).toList(),
                generatedClaims));
        if (!verification.verified()) {
            throw new IllegalArgumentException(
                    "Evidence validation failed: " + String.join(", ", verification.issueCodes()));
        }
        List<LessonStep> steps = IntStream.range(0, draft.steps().size())
                .mapToObj(index -> LessonDraftValidator.validatedStep(index + 1, draft.steps().get(index), allowedEvidence))
                .toList();
        List<Integer> visualSourcePages = visualCitationIds.stream()
                .map(allowedEvidence::get)
                .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .sorted()
                .boxed()
                .toList();
        return new LessonSection(
                planned.position(),
                planned.topicKey(),
                planned.coverageTags(),
                draft.title().strip(),
                planned.required(),
                evidenceStatus,
                draft.visualKind(),
                draft.visualCaption().strip(),
                visualSourcePages,
                visualCitationIds,
                steps);
    }

    private void reviewPublishedLesson(
            TeachingPlan plan,
            List<DraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher) {
        reviewPublishedBatch(
                plan,
                candidates,
                sections,
                assistantRunId,
                progressPublisher,
                MAX_POST_PUBLICATION_REVIEW_PASSES);
    }

    private boolean reviewPublishedBatch(
            TeachingPlan plan,
            List<DraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher,
            int remainingPasses) {
        LessonReviewBatch batch = lessonReviewBatch(candidates, assistantRunId);
        GeneratedContentCritic.Review review;
        try {
            review = critic.review(batch.request(), ReviewRisk.HIGH_IMPACT);
        } catch (AgentExecutionStoppedException stopped) {
            candidates.forEach(candidate -> recordPublication(
                    assistantRunId,
                    candidate.planned(),
                    ActivityOutcome.SUCCEEDED,
                    "POST_PUBLICATION_REVIEW_DEFERRED_RETAINING_CITED_DRAFT"));
            return true;
        } catch (RuntimeException reviewFailure) {
            log.warn("Whole-lesson factual review retained cited draft: {}", reviewFailure.getMessage());
            candidates.forEach(candidate -> recordPublication(
                    assistantRunId,
                    candidate.planned(),
                    ActivityOutcome.SUCCEEDED,
                    "POST_PUBLICATION_REVIEW_RETAINED_CITED_DRAFT"));
            return true;
        }

        Map<Integer, List<GeneratedContentCritic.Issue>> issuesBySection = review.issues().stream()
                .collect(Collectors.groupingBy(issue -> batch.claimOwners()
                        .get(issue.claimPosition()).sectionIndex()));
        List<DraftCandidate> correctedCandidates = new ArrayList<>();
        int factualCorrectionsStarted = 0;
        int scopeCorrectionsStarted = 0;
        for (DraftCandidate candidate : candidates) {
            List<GeneratedContentCritic.Issue> issues = issuesBySection.getOrDefault(
                    candidate.sectionIndex(), List.of());
            recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    0,
                    issues.isEmpty() ? ActivityOutcome.SUCCEEDED : ActivityOutcome.REJECTED,
                    issues.isEmpty() ? "POST_PUBLICATION_REVIEW_ACCEPTED" : criticDiagnostic(issues));
            boolean scopeOnly = issues.stream().allMatch(
                    issue -> issue.type() == GeneratedContentCritic.IssueType.CHAPTER_SCOPE_DUPLICATION);
            boolean correctionBudgetExhausted = scopeOnly
                    ? scopeCorrectionsStarted >= MAX_POST_PUBLICATION_SCOPE_CORRECTIONS
                    : factualCorrectionsStarted >= MAX_POST_PUBLICATION_REVIEW_CORRECTIONS;
            if (!issues.isEmpty() && correctionBudgetExhausted) {
                log.info(
                        "Whole-lesson review defers {} correction for topic {} after its immediate budget",
                        scopeOnly ? "chapter-scope" : "factual",
                        candidate.planned().topicKey());
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_REVIEW_DEFERRED_FOR_INCREMENTAL_REVIEW");
                continue;
            }
            try {
                DraftCandidate reviewed = issues.isEmpty()
                        ? new DraftCandidate(
                                candidate.sectionIndex(),
                                candidate.planned(),
                                candidate.evidence(),
                                candidate.modelRequest(),
                                candidate.draft(),
                                validatedSection(
                                plan,
                                candidate.planned(),
                                candidate.evidence(),
                                candidate.modelRequest(),
                                candidate.draft(),
                                EvidenceStatus.SUPPORTED))
                        : correctedPublishedDraft(plan, candidate, issues, assistantRunId);
                if (!issues.isEmpty()) {
                    if (scopeOnly) {
                        scopeCorrectionsStarted++;
                    } else {
                        factualCorrectionsStarted++;
                    }
                }
                sections.set(candidate.sectionIndex(), reviewed.section());
                if (!issues.isEmpty()) correctedCandidates.add(reviewed);
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        reviewed.section().evidenceStatus() == EvidenceStatus.SUPPORTED
                                ? "POST_PUBLICATION_REVIEW_ACCEPTED"
                                : "POST_PUBLICATION_REVIEW_PENDING");
                progressPublisher.run();
            } catch (AgentExecutionStoppedException stopped) {
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_REVIEW_DEFERRED_RETAINING_CITED_DRAFT");
                return true;
            } catch (RuntimeException correctionFailure) {
                log.warn(
                        "Whole-lesson review retained cited draft for topic {}: {}",
                        candidate.planned().topicKey(),
                        correctionFailure.getMessage());
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_REVIEW_RETAINED_CITED_DRAFT");
            }
        }
        if (!correctedCandidates.isEmpty() && remainingPasses > 1) {
            return reviewPublishedBatch(
                    plan,
                    correctedCandidates,
                    sections,
                    assistantRunId,
                    progressPublisher,
                    remainingPasses - 1);
        }
        return true;
    }

    private LessonReviewBatch lessonReviewBatch(List<DraftCandidate> candidates, UUID assistantRunId) {
        List<Claim> claims = new ArrayList<>();
        Map<Integer, DraftCandidate> claimOwners = new LinkedHashMap<>();
        Map<UUID, RuleEvidence> evidence = new LinkedHashMap<>();
        for (DraftCandidate candidate : candidates) {
            reviewEvidence(candidate).forEach(source -> evidence.putIfAbsent(source.chunkId(), source));
            List<UUID> visualCitationIds = LessonDraftValidator.validatedVisualCitationIds(
                    candidate.draft(),
                    candidate.evidence().stream().collect(Collectors.toUnmodifiableMap(
                            RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first)));
            for (Claim claim : LessonDraftValidator.reviewClaims(candidate.draft(), visualCitationIds)) {
                int position = claims.size() + 1;
                claims.add(new Claim(
                        position,
                        "第" + candidate.planned().position() + "章「" + candidate.planned().title() + "」："
                                + claim.text(),
                        claim.citationIds()));
                claimOwners.put(position, candidate);
            }
        }
        String objective = candidates.stream()
                .map(candidate -> "第" + candidate.planned().position() + "章「"
                        + candidate.planned().title() + "」：" + candidate.planned().objective())
                .collect(Collectors.joining("\n"));
        String requiredCoverage = candidates.stream()
                .map(candidate -> "第" + candidate.planned().position() + "章："
                        + requiredCoverage(candidate.planned()))
                .collect(Collectors.joining("\n"));
        Map<UUID, String> reviewExcerpts = candidates.stream()
                .flatMap(candidate -> candidate.modelRequest().evidence().stream())
                .collect(Collectors.toMap(
                        EvidenceInput::chunkId,
                        EvidenceInput::excerpt,
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        ReviewRequest request = new ReviewRequest(
                assistantRunId,
                ContentType.LESSON,
                ReviewMode.POST_PUBLICATION,
                new TaskContext(objective, requiredCoverage),
                claims,
                evidence.values().stream()
                        .map(source -> new GeneratedContentCritic.Evidence(
                                source.chunkId(), reviewExcerpts.getOrDefault(source.chunkId(), source.excerpt())))
                        .toList());
        return new LessonReviewBatch(request, Map.copyOf(claimOwners));
    }

    private List<RuleEvidence> reviewEvidence(DraftCandidate candidate) {
        Set<UUID> cited = Stream.concat(
                        candidate.draft().visualCitationIds().stream(),
                        candidate.draft().steps().stream().flatMap(step -> step.citationIds().stream()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, RuleEvidence> byId = candidate.evidence().stream()
                .collect(Collectors.toMap(
                        RuleEvidence::chunkId,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        List<RuleEvidence> selected = new ArrayList<>();
        cited.stream().map(byId::get).filter(java.util.Objects::nonNull).forEach(selected::add);
        candidate.evidence().stream()
                .filter(source -> !cited.contains(source.chunkId()))
                .limit(MAX_REVIEW_UNCITED_EVIDENCE_PER_SECTION)
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private DraftCandidate correctedPublishedDraft(
            TeachingPlan plan,
            DraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues,
            UUID assistantRunId) {
        List<String> feedback = List.of("Whole-lesson objective coverage review found: " + issues.stream()
                .map(issue -> issue.type() + " evidence=" + issue.evidenceIds() + " - " + issue.summary())
                .collect(Collectors.joining("; "))
                + ". Correct only from the supplied evidence and audit the entire revised section for new claims. "
                + chapterScopeCorrectionInstruction(issues)
                + "If any issue touches a worked example whose concrete species, component, quantity, pairing, or "
                + "board state is not directly stated in evidence, delete that concrete example or replace it with "
                + "a neutral procedure; do not invent a different example. Recount setup inventory against items "
                + "already moved out of a supply. Do not add a new factual sentence merely to replace a removed one.");
        SectionDraft corrected = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("correctTeachingSection", candidate.planned().position()),
                estimateTokens(candidate.modelRequest().toString()) + estimateTokens(candidate.draft().toString())
                        + estimateTokens(feedback.toString()),
                "Published teaching section corrected from whole-lesson review",
                () -> model.revise(candidate.modelRequest(), candidate.draft(), feedback),
                result -> estimateTokens(result.toString()));
        corrected = normalizeDraft(corrected, candidate.modelRequest());
        EvidenceStatus correctionStatus = corrected.equals(candidate.draft())
                ? EvidenceStatus.CITED_DRAFT
                : EvidenceStatus.SUPPORTED;
        LessonSection correctedSection;
        try {
            correctedSection = validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        } catch (IllegalArgumentException invalidCorrection) {
            SectionDraft invalidDraft = corrected;
            List<String> structuralRepair = new ArrayList<>(feedback);
            structuralRepair.add("The prior correction was structurally invalid: " + rejectionCategory(invalidCorrection)
                    + ". Return a complete replacement section with a short heading, teaching kind, text, and valid "
                    + "citations for every step. Preserve the requested correction; do not restore the removed claim.");
            corrected = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    operationName("repairCorrectedTeachingSection", candidate.planned().position()),
                    estimateTokens(candidate.modelRequest().toString()) + estimateTokens(invalidDraft.toString())
                            + estimateTokens(structuralRepair.toString()),
                    "Published teaching correction repaired to the section contract",
                    () -> model.revise(candidate.modelRequest(), invalidDraft, structuralRepair),
                    result -> estimateTokens(result.toString()));
            corrected = normalizeDraft(corrected, candidate.modelRequest());
            correctionStatus = corrected.equals(candidate.draft()) ? EvidenceStatus.CITED_DRAFT : EvidenceStatus.SUPPORTED;
            correctedSection = validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        }
        recordValidation(
                assistantRunId,
                candidate.planned(),
                1,
                ActivityOutcome.SUCCEEDED,
                "POST_PUBLICATION_CORRECTION_APPLIED");
        return new DraftCandidate(
                candidate.sectionIndex(),
                candidate.planned(),
                candidate.evidence(),
                candidate.modelRequest(),
                corrected,
                correctedSection);
    }

    private String chapterScopeCorrectionInstruction(List<GeneratedContentCritic.Issue> issues) {
        boolean hasScopeDuplication = issues.stream()
                .anyMatch(issue -> issue.type() == GeneratedContentCritic.IssueType.CHAPTER_SCOPE_DUPLICATION);
        if (!hasScopeDuplication) return "";
        return "For CHAPTER_SCOPE_DUPLICATION, retain the player-visible stage, order, or decision that this chapter "
                + "owns, but remove the nested cost, reward, exception, calculation, or component detail explicitly "
                + "assigned to a later chapter. Do not remove the stage altogether and do not replace it with a vague "
                + "promise; the later chapter remains responsible for the full detail. Remove the named duplicated "
                + "claim rather than paraphrasing the same full procedure more briefly. ";
    }

    private String requiredCoverage(TeachingPlan.PlannedSection planned) {
        return "Coverage tags: " + String.join(", ", planned.coverageTags())
                + "; required retrieval intents: " + String.join("; ", planned.retrievalQueries());
    }

    private static String chapterScope(TeachingPlan plan, TeachingPlan.PlannedSection current) {
        String chapters = plan.sections().stream()
                .map(section -> (section.position() == current.position() ? "【当前章节】" : "")
                        + "第" + section.position() + "章《" + section.title() + "》："
                        + boundedChapterObjective(section.objective()))
                .collect(Collectors.joining("\n"));
        String scope = "完整章节分工（仅界定讲解边界，不是规则事实）：\n" + chapters
                + "\n当前章节只完整讲解自己的目标。其他章节已经明确负责的机制，只保留本章理解所必需的"
                + "阶段名、顺序、即时选择或结果；不要复述它们的触发、数量、成本、例外、计算、完整流程或图例映射。";
        return scope.length() <= 4_000 ? scope : scope.substring(0, 3_999) + "…";
    }

    private static String boundedChapterObjective(String objective) {
        String value = objective == null ? "" : objective.strip();
        return value.length() <= 280 ? value : value.substring(0, 279) + "…";
    }

    static boolean claimsImmediateEndingForEndOfRoundTrigger(String playerText, List<RuleEvidence> citedEvidence) {
        return LessonDraftValidator.claimsImmediateEndingForEndOfRoundTrigger(playerText, citedEvidence);
    }

    static boolean defersCitedEndgameCheck(String playerText, List<RuleEvidence> citedEvidence) {
        return LessonDraftValidator.defersCitedEndgameCheck(playerText, citedEvidence);
    }

    private String rejectionCategory(IllegalArgumentException rejection) {
        String message = rejection.getMessage() == null ? "" : rejection.getMessage();
        if (message.startsWith("Evidence validation failed:")) {
            return "EVIDENCE_POLICY_" + message.substring(message.indexOf(':') + 1)
                    .replaceAll("[^A-Z0-9_, -]", "")
                    .strip()
                    .replaceAll("[, -]+", "+");
        }
        if (message.contains("unknown evidence reference")) return "UNKNOWN_EVIDENCE_REFERENCE";
        if (message.contains("visual cites evidence outside")) return "VISUAL_CITATION_OUTSIDE_SCOPE";
        if (message.contains("step cites evidence outside")) return "STEP_CITATION_OUTSIDE_SCOPE";
        if (message.contains("visual caption has no evidence")) return "VISUAL_CITATION_MISSING";
        if (message.contains("unresolved PDF icon")) return "UNRESOLVED_PDF_MARKER";
        if (message.contains("emoji icons")) return "UNRESOLVED_EMOJI_ICON";
        if (message.contains("do not end a rule")) return "STEP_TRUNCATED";
        if (message.contains("unanswered either/or alternative")) return "STEP_UNRESOLVED_ALTERNATIVE";
        if (message.contains("internal evidence or retrieval language")) return "INTERNAL_EVIDENCE_LANGUAGE";
        if (message.contains("internal short evidence references")) return "INTERNAL_EVIDENCE_REFERENCE";
        if (message.contains("source gap, pending rule")) return "PLAYER_FACING_SOURCE_GAP";
        if (message.contains("end condition occurs at the end of a round")) return "END_OF_ROUND_TIMING_LOST";
        if (message.contains("cited end-game check")) return "ENDGAME_CHECK_DEFERRED";
        if (message.contains("VISUAL") && message.contains("attached rulebook page")) return "VISUAL_PAGE_REQUIRED";
        if (message.contains("visual focus") || message.contains("focus region")) return "VISUAL_FOCUS_INVALID";
        if (message.contains("draft must contain")) return "STEP_COUNT_INVALID";
        if (message.contains("Every step needs")) return "STEP_METADATA_INVALID";
        if (message.contains("teaching step is invalid")) return "STEP_CONTENT_INVALID";
        if (message.contains("visual caption is missing")) return "VISUAL_CAPTION_MISSING";
        if (message.contains("visual caption is longer")) return "VISUAL_CAPTION_TOO_LONG";
        if (message.contains("visual caption")) return "VISUAL_CAPTION_INVALID";
        if (message.contains("title")) return "TITLE_INVALID";
        if (message.contains("visualKind")) return "VISUAL_KIND_MISSING";
        if (message.contains("draft is missing")) return "DRAFT_MISSING";
        return "SCHEMA_OR_POLICY_INVALID";
    }

    private String criticDiagnostic(List<GeneratedContentCritic.Issue> issues) {
        String diagnostic = "CRITIC_" + issues.stream()
                .collect(Collectors.groupingBy(
                        GeneratedContentCritic.Issue::type,
                        java.util.TreeMap::new,
                        Collectors.mapping(
                                GeneratedContentCritic.Issue::claimPosition,
                                Collectors.collectingAndThen(
                                        Collectors.toCollection(java.util.TreeSet::new),
                                        positions -> positions.stream()
                                                .map(String::valueOf)
                                                .collect(Collectors.joining(","))))))
                .entrySet().stream()
                .map(entry -> entry.getKey() + "@" + entry.getValue())
                .collect(Collectors.joining("+"));
        return diagnostic.length() <= 180 ? diagnostic : diagnostic.substring(0, 180);
    }

    private void recordValidation(
            UUID runId,
            TeachingPlan.PlannedSection section,
            int revision,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "validateTeachingSection|" + section.position() + "|" + revision,
                outcome,
                "Teaching draft " + (outcome == ActivityOutcome.SUCCEEDED ? "accepted: " : "rejected: ") + category);
    }

    private void recordVisualTextFallback(UUID runId, TeachingPlan.PlannedSection section) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "fallbackVisualTeachingSection|" + section.position(),
                ActivityOutcome.SUCCEEDED,
                "Visual composition unavailable; continuing with cited text");
    }

    private void recordPublication(
            UUID runId,
            TeachingPlan.PlannedSection section,
            ActivityOutcome outcome,
            String category) {
        invocations.record(
                runId,
                ActivityType.VALIDATION,
                "publishTeachingSection|" + section.position(),
                outcome,
                "Teaching section " + (outcome == ActivityOutcome.SUCCEEDED ? "published: " : "withheld: ") + category);
    }

    private record DraftCandidate(
            int sectionIndex,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft,
            LessonSection section) {}

    private record GenerationMode(
            boolean bindVisualPageEvidence,
            boolean includeVisualEvidence,
            String noEvidenceCategory,
            String invalidEvidenceCategory,
            String publishedCategory,
            String withheldCategory) {
        private static final GenerationMode PROGRESSIVE_BASE = new GenerationMode(
                true,
                false,
                "NO_VALID_BASE_EVIDENCE",
                "NO_VALID_BASE_EVIDENCE",
                "CITED_BASE_SECTION_PUBLISHED",
                "BASE_DRAFT_WITHHELD");
        private static final GenerationMode COMPATIBILITY_COMPLETE = new GenerationMode(
                false,
                true,
                "NO_RETRIEVED_EVIDENCE",
                "RETRIEVED_EVIDENCE_INVALID",
                "CITED_DRAFT_PUBLISHED",
                "DRAFT_WITHHELD_AFTER_REPAIR_BUDGET");
    }

    private enum EvidenceState { VERIFIED, EMPTY, INVALID }

    private record EvidenceResolution(List<RuleEvidence> evidence, int toolCalls, EvidenceState state) {
        boolean verified() {
            return state == EvidenceState.VERIFIED;
        }
    }

    private record SectionOutcome(
            int position,
            LessonSection section,
            DraftCandidate reviewCandidate,
            int retrievalToolCalls) {}

    private record LessonReviewBatch(
            ReviewRequest request,
            Map<Integer, DraftCandidate> claimOwners) {}

    private List<EvidenceInput> modelEvidence(UUID documentVersionId, List<RuleEvidence> evidence) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toSet());
        Map<Integer, String> factsByPage = visualFacts.find(documentVersionId, pages).stream()
                .collect(Collectors.toMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::evidenceText));
        return evidence.stream().map(source -> toModelEvidence(source, factsByPage)).toList();
    }

    private EvidenceInput toModelEvidence(RuleEvidence evidence, Map<Integer, String> factsByPage) {
        String visualFact = evidence.pageFrom() == evidence.pageTo() ? factsByPage.get(evidence.pageFrom()) : null;
        String excerpt = visualFact == null ? evidence.excerpt() : evidence.excerpt() + "\n\n" + visualFact;
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                excerpt,
                evidence.pageFrom(),
                evidence.pageTo());
    }

    private boolean sameEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo();
    }

    private List<PriorSectionContext> continuityContext(List<LessonSection> sections) {
        List<LessonSection> supported = sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .toList();
        int fromIndex = Math.max(0, supported.size() - 2);
        return supported.subList(fromIndex, supported.size()).stream()
                .map(section -> new PriorSectionContext(
                        section.topicKey(), section.title(), section.steps().getLast().text()))
                .toList();
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
    }

    private LessonSection insufficient(TeachingPlan.PlannedSection planned) {
        return new LessonSection(
                planned.position(),
                planned.topicKey(),
                planned.coverageTags(),
                planned.title(),
                planned.required(),
                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                VisualKind.REFERENCE_CARD,
                "本节等待可验证的规则证据",
                List.of(new LessonStep(
                        1,
                        "暂时跳过",
                        TeachingMove.WATCH,
                        "规则资料中尚未找到这一节所需的可靠证据。",
                        List.of(),
                        List.of())));
    }

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
