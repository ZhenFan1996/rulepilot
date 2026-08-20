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
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
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
    static final String GENERATOR_VERSION = "adaptive-teaching-v58-whole-game-context";
    private static final Set<String> REUSABLE_GENERATOR_VERSIONS =
            Set.of(GENERATOR_VERSION);
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionEvidenceRetriever evidenceRetriever;
    private final TeachingEvidenceRefiner evidenceRefiner;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingBaseSectionPublicationPolicy basePublication =
            new TeachingBaseSectionPublicationPolicy();
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
                    int baseMaxRetrievalQueriesPerSection,
            TeachingEvidenceRefiner evidenceRefiner) {
        this.tools = tools;
        this.model = model;
        this.invocations = invocations;
        TeachingVisualEvidenceResolver visualEvidenceResolver = new TeachingVisualEvidenceResolver(
                tools, invocations, visualFacts, visualCatalog);
        this.evidenceRetriever = new TeachingSectionEvidenceRetriever(
                tools, evidenceVerifier, invocations, visualEvidenceResolver);
        this.evidenceRefiner = evidenceRefiner;
        this.sectionDraftComposer = new TeachingSectionDraftComposer(
                model, evidenceVerifier, invocations, visualFacts);
        this.publishedLessonReviewer = new TeachingPublishedLessonReviewer(
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
            int baseSectionParallelism,
            int baseMaxRetrievalQueriesPerSection) {
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
                baseMaxRetrievalQueriesPerSection,
                null);
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
     * Completes the compatibility review workflow in one call. The production launcher uses
     * {@link #createBase(TeachingPlan, UUID, IllustratedLesson, Consumer)} so a player receives
     * deterministically validated chapters without waiting for an optional model review.
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
        return continueBase(
                startBase(plan, assistantRunId, previousLesson, progressPublisher),
                progressPublisher);
    }

    /**
     * Publishes through the first source-cited section before the remaining sections are queued.
     * An evidence-insufficient early topic is persisted truthfully, then startup advances in plan order until useful
     * cited content exists or every topic is known to be insufficient.
     * The returned continuation is process-local on purpose: an application restart fails the owning Assistant Run,
     * while the already persisted first section remains readable and an explicit retry rebuilds from durable state.
     */
    BaseLessonContinuation startBase(
            TeachingPlan plan,
            UUID assistantRunId,
            IllustratedLesson previousLesson,
            Consumer<IllustratedLesson> progressPublisher) {
        if (progressPublisher == null) throw new IllegalArgumentException("lesson progress publisher is required");
        TeachingWholeGameUnderstandingPolicy.validateBeforeChapterGeneration(plan);
        if (TeachingWholeGameUnderstandingPolicy.requiresValidatedContext(plan)) {
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "validateWholeGameTeachingContext",
                    ActivityOutcome.SUCCEEDED,
                    "Source-bound whole-game understanding completed before chapter generation");
        }
        UUID lessonId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Map<String, LessonSection> reusable = reusableSections(plan, previousLesson);
        int queriesPerTopic = baseQueryBudget(plan);
        List<LessonSection> sections = new ArrayList<>();
        Set<Integer> invalidDraftPositions = new LinkedHashSet<>();
        Set<Integer> missingEvidencePositions = new LinkedHashSet<>();
        int retrievalToolCalls = 0;
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            SectionOutcome outcome = baseSection(
                    plan,
                    planned,
                    lessonAssembly.continuityContext(sections),
                    reusable,
                    assistantRunId,
                    queriesPerTopic);
            retrievalToolCalls += outcome.retrievalToolCalls();
            trackFailure(outcome, invalidDraftPositions, missingEvidencePositions);
            sections.add(outcome.section());
            publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
            recordPublication(
                    assistantRunId,
                    planned,
                    outcome.publicationOutcome(),
                    outcome.publicationCategory());
            if (outcome.section().evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE) break;
        }

        return new BaseLessonContinuation(
                plan,
                assistantRunId,
                lessonId,
                createdAt,
                reusable,
                queriesPerTopic,
                sections,
                invalidDraftPositions,
                missingEvidencePositions,
                retrievalToolCalls);
    }

    IllustratedLesson continueBase(
            BaseLessonContinuation continuation,
            Consumer<IllustratedLesson> progressPublisher) {
        if (continuation == null || progressPublisher == null) {
            throw new IllegalArgumentException("lesson continuation and progress publisher are required");
        }
        TeachingPlan plan = continuation.plan;
        UUID assistantRunId = continuation.assistantRunId;
        UUID lessonId = continuation.lessonId;
        Instant createdAt = continuation.createdAt;
        Map<String, LessonSection> reusable = continuation.reusableSections;
        int queriesPerTopic = continuation.queriesPerTopic;
        List<LessonSection> sections = continuation.sections;
        List<TeachingPlan.PlannedSection> remaining = plan.sections().subList(sections.size(), plan.sections().size());
        if (!remaining.isEmpty()) {
            // The first section is already durable before this method runs. A progressive visual plan can therefore
            // fill the remaining page-fact ledger now without extending the player's wait for first useful content.
            evidenceRetriever.prefetchRemainingVisualFacts(plan, sections.size(), assistantRunId);
            List<PriorSectionContext> sharedContext = lessonAssembly.continuityContext(sections);
            Map<Integer, SectionOutcome> completed = new LinkedHashMap<>();
            int providerParallelism = Math.max(1, model.maxConcurrentSectionRequests(plan.createdBy()));
            int effectiveParallelism = Math.min(
                    remaining.size(),
                    Math.min(baseSectionParallelism, providerParallelism));
            try (var executor = Executors.newFixedThreadPool(effectiveParallelism)) {
                List<Future<SectionOutcome>> futures = remaining.stream()
                        .map(planned -> executor.submit(() -> baseSection(
                                plan,
                                planned,
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
                        continuation.track(contiguous);
                        sections.add(contiguous.section());
                        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                        recordPublication(
                                assistantRunId,
                                contiguous.planned(),
                                contiguous.publicationOutcome(),
                                contiguous.publicationCategory());
                    }
                }
            }
        }
        IllustratedLesson firstPass = lesson(lessonId, plan, sections, createdAt);
        if (firstPass.status() == LessonStatus.INCOMPLETE
                && continuation.canRetryInvalidDrafts(maxToolCalls)) {
            return retryInvalidDraftSections(continuation, progressPublisher);
        }
        return firstPass;
    }

    private IllustratedLesson retryInvalidDraftSections(
            BaseLessonContinuation continuation,
            Consumer<IllustratedLesson> progressPublisher) {
        List<Integer> positions = List.copyOf(continuation.invalidDraftPositions);
        invocations.record(
                continuation.assistantRunId,
                ActivityType.VALIDATION,
                "retryIncompleteTeachingSections",
                ActivityOutcome.SUCCEEDED,
                "Retrying " + positions.size() + " cited sections whose generated draft did not pass validation");
        for (int position : positions) {
            int remainingToolBudget = maxToolCalls - continuation.retrievalToolCalls;
            if (remainingToolBudget <= 0) break;
            TeachingPlan.PlannedSection planned = continuation.plan.sections().get(position - 1);
            SectionOutcome outcome = baseSection(
                    continuation.plan,
                    planned,
                    lessonAssembly.continuityContext(continuation.sections),
                    continuation.reusableSections,
                    continuation.assistantRunId,
                    Math.min(continuation.queriesPerTopic, remainingToolBudget),
                    false);
            continuation.replace(outcome);
            publishProgress(
                    progressPublisher,
                    continuation.lessonId,
                    continuation.plan,
                    continuation.sections,
                    continuation.createdAt);
            recordPublication(
                    continuation.assistantRunId,
                    planned,
                    outcome.publicationOutcome(),
                    outcome.publicationCategory());
        }
        return lesson(
                continuation.lessonId,
                continuation.plan,
                continuation.sections,
                continuation.createdAt);
    }

    private static void trackFailure(
            SectionOutcome outcome,
            Set<Integer> invalidDraftPositions,
            Set<Integer> missingEvidencePositions) {
        if (outcome.failure() == SectionFailure.INVALID_DRAFT) {
            invalidDraftPositions.add(outcome.position());
        } else if (outcome.failure() == SectionFailure.MISSING_EVIDENCE) {
            missingEvidencePositions.add(outcome.position());
        }
    }

    static final class BaseLessonContinuation {
        private final TeachingPlan plan;
        private final UUID assistantRunId;
        private final UUID lessonId;
        private final Instant createdAt;
        private final Map<String, LessonSection> reusableSections;
        private final int queriesPerTopic;
        private final List<LessonSection> sections;
        private final Set<Integer> invalidDraftPositions;
        private final Set<Integer> missingEvidencePositions;
        private int retrievalToolCalls;

        private BaseLessonContinuation(
                TeachingPlan plan,
                UUID assistantRunId,
                UUID lessonId,
                Instant createdAt,
                Map<String, LessonSection> reusableSections,
                int queriesPerTopic,
                List<LessonSection> sections,
                Set<Integer> invalidDraftPositions,
                Set<Integer> missingEvidencePositions,
                int retrievalToolCalls) {
            this.plan = plan;
            this.assistantRunId = assistantRunId;
            this.lessonId = lessonId;
            this.createdAt = createdAt;
            this.reusableSections = reusableSections;
            this.queriesPerTopic = queriesPerTopic;
            this.sections = sections;
            this.invalidDraftPositions = invalidDraftPositions;
            this.missingEvidencePositions = missingEvidencePositions;
            this.retrievalToolCalls = retrievalToolCalls;
        }

        boolean hasRemainingWork() {
            return sections.size() < plan.sections().size();
        }

        private void track(SectionOutcome outcome) {
            retrievalToolCalls += outcome.retrievalToolCalls();
            trackFailure(outcome, invalidDraftPositions, missingEvidencePositions);
        }

        private void replace(SectionOutcome outcome) {
            int position = outcome.position();
            sections.set(position - 1, outcome.section());
            invalidDraftPositions.remove(position);
            missingEvidencePositions.remove(position);
            track(outcome);
        }

        private boolean canRetryInvalidDrafts(int maxToolCalls) {
            return missingEvidencePositions.isEmpty()
                    && !invalidDraftPositions.isEmpty()
                    && maxToolCalls - retrievalToolCalls >= invalidDraftPositions.size();
        }
    }

    private SectionOutcome baseSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queriesPerTopic) {
        return baseSection(
                plan,
                planned,
                priorSections,
                reusableSections,
                assistantRunId,
                queriesPerTopic,
                true);
    }

    private SectionOutcome baseSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queriesPerTopic,
            boolean allowValidationRevision) {
        return generateSection(
                plan,
                planned,
                priorSections,
                reusableSections,
                assistantRunId,
                queriesPerTopic,
                planned.position() - 1,
                GenerationMode.PROGRESSIVE_BASE,
                allowValidationRevision);
    }

    private int baseQueryBudget(TeachingPlan plan) {
        int shareOfGlobalBudget = Math.max(1, maxToolCalls / plan.sections().size());
        return Math.min(baseMaxRetrievalQueriesPerSection, shareOfGlobalBudget);
    }

    private SectionOutcome generateSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queryBudget,
            int sectionIndex,
            GenerationMode mode,
            boolean allowValidationRevision) {
        LessonSection reusable = reusableSections.get(planned.topicKey());
        if (reusable != null) {
            return new SectionOutcome(
                    planned.position(), planned, reusable, null, 0,
                    ActivityOutcome.SUCCEEDED, "REUSED_VERIFIED_SECTION", SectionFailure.NONE);
        }
        TeachingSectionEvidenceRetriever.Result resolution = evidenceRetriever.retrieve(
                plan, planned, assistantRunId, queryBudget, mode.bindVisualPageEvidence());
        if (evidenceRefiner != null) {
            try {
                resolution = evidenceRefiner.refine(plan, planned, assistantRunId, resolution);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException optionalRefinementFailure) {
                log.warn(
                        "Optional teaching evidence refinement failed for topic {}; retaining verified base evidence: {}",
                        planned.topicKey(),
                        optionalRefinementFailure.getMessage());
            }
        }
        if (!resolution.verified()) {
            return new SectionOutcome(
                    planned.position(),
                    planned,
                    lessonAssembly.insufficient(planned),
                    null,
                    resolution.toolCalls(),
                    ActivityOutcome.REJECTED,
                    resolution.state() == TeachingSectionEvidenceRetriever.State.EMPTY
                            ? mode.noEvidenceCategory()
                            : mode.invalidEvidenceCategory(),
                    SectionFailure.MISSING_EVIDENCE);
        }
        try {
            TeachingSectionDraftCandidate composed = sectionDraftComposer.compose(
                    plan,
                    planned,
                    priorSections,
                    resolution.evidence(),
                    assistantRunId,
                    sectionIndex,
                    mode.includeVisualEvidence() && planned.visualEvidenceRecommended(),
                    allowValidationRevision);
            LessonSection published = mode.publishAfterDeterministicValidation()
                    ? basePublication.publish(composed)
                    : composed.section();
            return new SectionOutcome(
                    planned.position(), planned, published, composed, resolution.toolCalls(),
                    ActivityOutcome.SUCCEEDED, mode.publishedCategory(), SectionFailure.NONE);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidDraft) {
            log.warn("Teaching section {} was withheld: {}", planned.topicKey(), invalidDraft.getMessage());
            return new SectionOutcome(
                    planned.position(), planned, lessonAssembly.insufficient(planned), null, resolution.toolCalls(),
                    ActivityOutcome.REJECTED, mode.withheldCategory(), SectionFailure.INVALID_DRAFT);
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
        int toolCalls = 0;
        int queriesPerTopic = Math.max(1, Math.min(6, maxToolCalls / plan.sections().size()));
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            boolean reusable = reusableSections.containsKey(planned.topicKey());
            if (!reusable && toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before topic {}", planned.topicKey());
                sections.add(lessonAssembly.insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "TOOL_BUDGET_EXHAUSTED");
                continue;
            }
            SectionOutcome outcome = generateSection(
                    plan,
                    planned,
                    lessonAssembly.continuityContext(sections),
                    reusableSections,
                    assistantRunId,
                    Math.min(queriesPerTopic, maxToolCalls - toolCalls),
                    sections.size(),
                    GenerationMode.COMPATIBILITY_COMPLETE,
                    true);
            toolCalls += outcome.retrievalToolCalls();
            sections.add(outcome.section());
            if (outcome.reviewCandidate() != null) reviewCandidates.add(outcome.reviewCandidate());
            publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
            recordPublication(
                    assistantRunId,
                    planned,
                    outcome.publicationOutcome(),
                    outcome.publicationCategory());
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
            boolean publishAfterDeterministicValidation,
            String noEvidenceCategory,
            String invalidEvidenceCategory,
            String publishedCategory,
            String withheldCategory) {
        private static final GenerationMode PROGRESSIVE_BASE = new GenerationMode(
                true,
                false,
                true,
                "NO_VALID_BASE_EVIDENCE",
                "BASE_EVIDENCE_IDENTITY_INVALID",
                "CITED_BASE_SECTION_PUBLISHED",
                "BASE_DRAFT_WITHHELD");
        private static final GenerationMode COMPATIBILITY_COMPLETE = new GenerationMode(
                false,
                true,
                false,
                "NO_RETRIEVED_EVIDENCE",
                "RETRIEVED_EVIDENCE_INVALID",
                "CITED_DRAFT_PUBLISHED",
                "DRAFT_WITHHELD_AFTER_REPAIR_BUDGET");
    }

    private record SectionOutcome(
            int position,
            TeachingPlan.PlannedSection planned,
            LessonSection section,
            TeachingSectionDraftCandidate reviewCandidate,
            int retrievalToolCalls,
            ActivityOutcome publicationOutcome,
            String publicationCategory,
            SectionFailure failure) {}

    private enum SectionFailure {
        NONE,
        MISSING_EVIDENCE,
        INVALID_DRAFT
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
