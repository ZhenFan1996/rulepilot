package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionEvidenceRetriever evidenceRetriever;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingLessonAssemblyPolicy lessonAssembly = new TeachingLessonAssemblyPolicy();
    private final TeachingPublishedLessonReviewer publishedLessonReviewer;
    private final int maxToolCalls;
    private final int baseSectionParallelism;
    private final int baseMaxRetrievalQueriesPerSection;

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
            @Value("${rulepilot.teaching.base-section-parallelism:3}") int baseSectionParallelism,
            @Value("${rulepilot.teaching.base-max-retrieval-queries-per-section:3}")
                    int baseMaxRetrievalQueriesPerSection) {
        this.tools = tools;
        this.model = model;
        this.invocations = invocations;
        TeachingVisualEvidenceResolver visualEvidenceResolver = new TeachingVisualEvidenceResolver(
                tools, invocations, visualFacts, visualCatalog);
        this.evidenceRetriever = new TeachingSectionEvidenceRetriever(
                tools, evidenceVerifier, invocations, visualEvidenceResolver);
        this.sectionDraftComposer = new TeachingSectionDraftComposer(
                model, evidenceVerifier, invocations, visualFacts);
        this.publishedLessonReviewer = new TeachingPublishedLessonReviewer(
                model,
                critic,
                invocations,
                sectionDraftComposer,
                new TeachingReviewCorrectionPolicy());
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.baseSectionParallelism = Math.max(1, Math.min(6, baseSectionParallelism));
        this.baseMaxRetrievalQueriesPerSection = Math.max(1, baseMaxRetrievalQueriesPerSection);
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookPageCatalogModel visualCatalog,
            int maxToolCalls,
            int baseSectionParallelism) {
        this(
                tools,
                model,
                evidenceVerifier,
                critic,
                invocations,
                visualFacts,
                visualCatalog,
                maxToolCalls,
                baseSectionParallelism,
                3);
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
                baseSectionParallelism,
                3);
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
                baseSectionParallelism,
                3);
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
                3,
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
        int queriesPerTopic = baseQueryBudget(plan);
        List<LessonSection> sections = new ArrayList<>();
        List<TeachingSectionDraftCandidate> reviewCandidates = new ArrayList<>();
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
            List<PriorSectionContext> sharedContext = lessonAssembly.continuityContext(sections);
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
            publishedLessonReviewer.review(
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

    private int baseQueryBudget(TeachingPlan plan) {
        int shareOfGlobalBudget = Math.max(1, maxToolCalls / plan.sections().size());
        return Math.min(baseMaxRetrievalQueriesPerSection, shareOfGlobalBudget);
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
        TeachingSectionEvidenceRetriever.Result resolution = evidenceRetriever.retrieve(
                plan, planned, assistantRunId, queryBudget, mode.bindVisualPageEvidence());
        if (!resolution.verified()) {
            recordPublication(
                    assistantRunId,
                    planned,
                    ActivityOutcome.REJECTED,
                    resolution.state() == TeachingSectionEvidenceRetriever.State.EMPTY
                            ? mode.noEvidenceCategory()
                            : mode.invalidEvidenceCategory());
            return new SectionOutcome(planned.position(), lessonAssembly.insufficient(planned), null, resolution.toolCalls());
        }
        try {
            TeachingSectionDraftCandidate composed = sectionDraftComposer.compose(
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
            return new SectionOutcome(planned.position(), lessonAssembly.insufficient(planned), null, resolution.toolCalls());
        }
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
        List<TeachingSectionDraftCandidate> reviewCandidates = new ArrayList<>();
        Map<String, LessonSection> reusableSections = reusableSections(plan, previousLesson);
        Map<Integer, TeachingPacingPolicy.SectionPacing> pacing = TeachingPacingPolicy.allocate(plan);
        int toolCalls = 0;
        int queriesPerTopic = Math.max(1, Math.min(6, maxToolCalls / plan.sections().size()));
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            boolean reusable = reusableSections.containsKey(planned.topicKey());
            if (!reusable && toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before topic {}", planned.topicKey());
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "TOOL_BUDGET_EXHAUSTED");
                sections.add(lessonAssembly.insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }
            SectionOutcome outcome = generateSection(
                    plan,
                    planned,
                    pacing.get(planned.position()),
                    lessonAssembly.continuityContext(sections),
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
            publishedLessonReviewer.review(
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
        return lessonAssembly.snapshot(lessonId, plan, sections, GENERATOR_VERSION, createdAt);
    }

    private void publishProgress(
            Consumer<IllustratedLesson> progressPublisher,
            UUID lessonId,
            TeachingPlan plan,
            List<LessonSection> sections,
            Instant createdAt) {
        progressPublisher.accept(lessonAssembly.snapshot(lessonId, plan, sections, GENERATOR_VERSION, createdAt));
    }

    private Map<String, LessonSection> reusableSections(
            TeachingPlan plan, IllustratedLesson previousLesson) {
        return lessonAssembly.reusableSections(
                plan,
                previousLesson,
                REUSABLE_GENERATOR_VERSIONS,
                model.supportsVisualEvidence(plan.createdBy()));
    }

    static boolean claimsImmediateEndingForEndOfRoundTrigger(String playerText, List<RuleEvidence> citedEvidence) {
        return LessonDraftValidator.claimsImmediateEndingForEndOfRoundTrigger(playerText, citedEvidence);
    }

    static boolean defersCitedEndgameCheck(String playerText, List<RuleEvidence> citedEvidence) {
        return LessonDraftValidator.defersCitedEndgameCheck(playerText, citedEvidence);
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

    private record SectionOutcome(
            int position,
            LessonSection section,
            TeachingSectionDraftCandidate reviewCandidate,
            int retrievalToolCalls) {}

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
