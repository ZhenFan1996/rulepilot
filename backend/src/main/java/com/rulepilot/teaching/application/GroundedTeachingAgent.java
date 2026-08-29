package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GroundedTeachingAgent {

    private static final Logger log = LoggerFactory.getLogger(GroundedTeachingAgent.class);
    static final String GENERATOR_VERSION = "adaptive-teaching-v59-post-model-visual-ownership";
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
    private final TeachingRunWorkloadPolicy workloadPolicy;
    private final VisualLessonEnricher visualEnricher;

    @Autowired
    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            @Value("${rulepilot.teaching.base-max-retrieval-queries-per-section:3}")
                    int baseMaxRetrievalQueriesPerSection,
            TeachingEvidenceRefiner evidenceRefiner,
            VisualRulebookCataloger visualCataloger,
            VisualLessonEnricher visualEnricher) {
        this.tools = tools;
        this.model = model;
        this.invocations = invocations;
        TeachingVisualEvidenceResolver visualEvidenceResolver = new TeachingVisualEvidenceResolver(
                tools, invocations, visualFacts, visualCataloger);
        this.evidenceRetriever = new TeachingSectionEvidenceRetriever(
                tools, evidenceVerifier, invocations, visualEvidenceResolver);
        this.evidenceRefiner = evidenceRefiner;
        this.sectionDraftComposer = new TeachingSectionDraftComposer(
                model, evidenceVerifier, invocations, visualFacts);
        this.publishedLessonReviewer = new TeachingPublishedLessonReviewer(
                critic, invocations, sectionDraftComposer);
        this.workloadPolicy = new TeachingRunWorkloadPolicy(Math.max(1, baseMaxRetrievalQueriesPerSection));
        this.visualEnricher = visualEnricher;
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            int baseMaxRetrievalQueriesPerSection,
            TeachingEvidenceRefiner evidenceRefiner,
            VisualRulebookCataloger visualCataloger) {
        this(
                tools,
                model,
                evidenceVerifier,
                critic,
                invocations,
                visualFacts,
                baseMaxRetrievalQueriesPerSection,
                evidenceRefiner,
                visualCataloger,
                null);
    }

    /**
     * Completes the compatibility review workflow in one call. The production launcher uses
     * {@link #createBase(TeachingPlan, UUID, IllustratedLesson, Consumer)} so a player receives cited chapters
     * incrementally while quantitative and legality-changing chapters remain visibly provisional until one bounded
     * whole-lesson review completes.
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
        BaseLessonContinuation continuation =
                startBase(plan, assistantRunId, previousLesson, progressPublisher);
        BaseWorkUnitResult result;
        do {
            result = continueBaseWorkUnit(continuation, progressPublisher);
        } while (!result.complete());
        return result.lesson();
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
        int queriesPerTopic = baseQueryBudget();
        List<LessonSection> sections = new ArrayList<>();
        List<TeachingSectionDraftCandidate> reviewCandidates = new ArrayList<>();
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            SectionOutcome outcome = baseSection(
                    plan,
                    planned,
                    lessonAssembly.continuityContext(sections),
                    reusable,
                    assistantRunId,
                    queriesPerTopic);
            outcome = publishValidatedSectionThenEnrich(
                    plan,
                    outcome,
                    sections,
                    assistantRunId,
                    lessonId,
                    createdAt,
                    progressPublisher);
            if (outcome.reviewCandidate() != null
                    && outcome.section().evidenceStatus() == EvidenceStatus.CITED_DRAFT) {
                reviewCandidates.add(outcome.reviewCandidate());
            }
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
                reviewCandidates);
    }

    IllustratedLesson continueBase(
            BaseLessonContinuation continuation,
            Consumer<IllustratedLesson> progressPublisher) {
        BaseWorkUnitResult result;
        do {
            result = continueBaseWorkUnit(continuation, progressPublisher);
        } while (!result.complete());
        return result.lesson();
    }

    BaseWorkUnitResult continueBaseWorkUnit(
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
        if (continuation.hasRemainingWork()) {
            TeachingPlan.PlannedSection planned = plan.sections().get(sections.size());
            SectionOutcome outcome = baseSection(
                    plan,
                    planned,
                    lessonAssembly.continuityContext(sections),
                    reusable,
                    assistantRunId,
                    queriesPerTopic);
            outcome = publishValidatedSectionThenEnrich(
                    plan,
                    outcome,
                    sections,
                    assistantRunId,
                    lessonId,
                    createdAt,
                    progressPublisher);
            continuation.track(outcome);
            if (continuation.hasRemainingWork()) {
                return new BaseWorkUnitResult(lesson(lessonId, plan, sections, createdAt), false);
            }
        }
        IllustratedLesson firstPass = lesson(lessonId, plan, sections, createdAt);
        if (firstPass.status() == LessonStatus.DRAFT_READY && !continuation.reviewCandidates.isEmpty()) {
            TeachingPublishedLessonReviewer.ReviewResult reviewResult = publishedLessonReviewer.review(
                    plan,
                    List.copyOf(continuation.reviewCandidates),
                    sections,
                    assistantRunId,
                    () -> progressPublisher.accept(lesson(lessonId, plan, sections, createdAt)));
            reEnrichAcceptedReplacements(
                    plan,
                    reviewResult,
                    sections,
                    assistantRunId,
                    () -> progressPublisher.accept(lesson(lessonId, plan, sections, createdAt)));
            return new BaseWorkUnitResult(lesson(lessonId, plan, sections, createdAt), true);
        }
        return new BaseWorkUnitResult(firstPass, true);
    }

    static final class BaseLessonContinuation {
        private final TeachingPlan plan;
        private final UUID assistantRunId;
        private final UUID lessonId;
        private final Instant createdAt;
        private final Map<String, LessonSection> reusableSections;
        private final int queriesPerTopic;
        private final List<LessonSection> sections;
        private final List<TeachingSectionDraftCandidate> reviewCandidates;

        private BaseLessonContinuation(
                TeachingPlan plan,
                UUID assistantRunId,
                UUID lessonId,
                Instant createdAt,
                Map<String, LessonSection> reusableSections,
                int queriesPerTopic,
                List<LessonSection> sections,
                List<TeachingSectionDraftCandidate> reviewCandidates) {
            this.plan = plan;
            this.assistantRunId = assistantRunId;
            this.lessonId = lessonId;
            this.createdAt = createdAt;
            this.reusableSections = reusableSections;
            this.queriesPerTopic = queriesPerTopic;
            this.sections = sections;
            this.reviewCandidates = reviewCandidates;
        }

        boolean hasRemainingWork() {
            return sections.size() < plan.sections().size();
        }

        private void track(SectionOutcome outcome) {
            if (outcome.reviewCandidate() != null
                    && outcome.section().evidenceStatus() == EvidenceStatus.CITED_DRAFT) {
                reviewCandidates.add(outcome.reviewCandidate());
            }
        }

    }

    record BaseWorkUnitResult(IllustratedLesson lesson, boolean complete) {
        BaseWorkUnitResult {
            if (lesson == null) throw new IllegalArgumentException("lesson work unit result is required");
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
                GenerationMode.BASE,
                allowValidationRevision);
    }

    private int baseQueryBudget() {
        return workloadPolicy.maxRetrievalQueriesPerSection();
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
        TeachingModelCallBudget modelCallBudget = TeachingModelCallBudget.section();
        LessonSection reusable = reusableSections.get(planned.topicKey());
        if (reusable != null) {
            return new SectionOutcome(
                    planned.position(), planned, reusable, null,
                    ActivityOutcome.SUCCEEDED,
                    "REUSED_VERIFIED_SECTION");
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
        return composeResolvedSection(
                plan,
                planned,
                priorSections,
                assistantRunId,
                resolution,
                sectionIndex,
                mode,
                allowValidationRevision,
                modelCallBudget);
    }

    private SectionOutcome composeResolvedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result resolution,
            int sectionIndex,
            GenerationMode mode,
            boolean allowValidationRevision,
            TeachingModelCallBudget modelCallBudget) {
        if (!resolution.verified()) {
            return new SectionOutcome(
                    planned.position(),
                    planned,
                    lessonAssembly.insufficient(planned),
                    null,
                    ActivityOutcome.REJECTED,
                    resolution.state() == TeachingSectionEvidenceRetriever.State.EMPTY
                            ? mode.noEvidenceCategory()
                            : mode.invalidEvidenceCategory());
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
                    allowValidationRevision,
                    modelCallBudget);
            LessonSection published = mode.publishAfterDeterministicValidation()
                    ? basePublication.publish(composed)
                    : composed.section();
            return new SectionOutcome(
                    planned.position(), planned, published, composed,
                    ActivityOutcome.SUCCEEDED,
                    mode.publishedCategory());
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidDraft) {
            log.warn("Teaching section {} was withheld: {}", planned.topicKey(), invalidDraft.getMessage());
            return new SectionOutcome(
                    planned.position(), planned, lessonAssembly.insufficient(planned), null,
                    ActivityOutcome.REJECTED,
                    mode.withheldCategory());
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
        int queriesPerTopic = baseQueryBudget();
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            SectionOutcome outcome = generateSection(
                    plan,
                    planned,
                    lessonAssembly.continuityContext(sections),
                    reusableSections,
                    assistantRunId,
                    queriesPerTopic,
                    sections.size(),
                    GenerationMode.COMPATIBILITY_COMPLETE,
                    true);
            outcome = publishValidatedSectionThenEnrich(
                    plan,
                    outcome,
                    sections,
                    assistantRunId,
                    lessonId,
                    createdAt,
                    progressPublisher);
            if (outcome.reviewCandidate() != null
                    && outcome.section().evidenceStatus() == EvidenceStatus.CITED_DRAFT) {
                reviewCandidates.add(outcome.reviewCandidate());
            }
        }

        IllustratedLesson draftReady = lesson(lessonId, plan, sections, createdAt);
        if (draftReady.status() == LessonStatus.DRAFT_READY && !reviewCandidates.isEmpty()) {
            TeachingPublishedLessonReviewer.ReviewResult reviewResult = publishedLessonReviewer.review(
                    plan,
                    reviewCandidates,
                    sections,
                    assistantRunId,
                    () -> progressPublisher.accept(lesson(lessonId, plan, sections, createdAt)));
            reEnrichAcceptedReplacements(
                    plan,
                    reviewResult,
                    sections,
                    assistantRunId,
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

    private SectionOutcome publishValidatedSectionThenEnrich(
            TeachingPlan plan,
            SectionOutcome outcome,
            List<LessonSection> sections,
            UUID assistantRunId,
            UUID lessonId,
            Instant createdAt,
            Consumer<IllustratedLesson> progressPublisher) {
        int sectionIndex = sections.size();
        LessonSection citedText = outcome.section();
        sections.add(citedText);
        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
        recordPublication(
                assistantRunId,
                outcome.planned(),
                outcome.publicationOutcome(),
                outcome.publicationCategory());

        LessonSection enriched = enrichValidatedSection(
                plan,
                outcome.planned(),
                citedText,
                List.copyOf(sections.subList(0, sectionIndex)),
                assistantRunId);
        if (!enriched.equals(citedText)) {
            sections.set(sectionIndex, enriched);
            publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
        }
        return outcome.withSection(enriched);
    }

    private LessonSection enrichValidatedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            LessonSection section,
            List<LessonSection> alreadyPublished,
            UUID assistantRunId) {
        if (visualEnricher == null
                || section.evidenceStatus() == EvidenceStatus.INSUFFICIENT_EVIDENCE
                || !visualEnricher.supportsVisualEvidence(plan.createdBy())) {
            return section;
        }
        try {
            var enriched = visualEnricher.enrichSection(
                    plan.documentVersionId(),
                    section,
                    List.copyOf(alreadyPublished),
                    plan.createdBy(),
                    assistantRunId,
                    new VisualLessonEnricher.VisualProgressListener() {});
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "enrichTeachingSectionVisual|" + planned.position(),
                    enriched.outcome().outcome() == VisualLessonEnricher.Outcome.ADDED
                                    || enriched.outcome().outcome()
                                            == VisualLessonEnricher.Outcome.ADDED_WITH_CLAIM_CONFLICT
                            ? ActivityOutcome.SUCCEEDED
                            : ActivityOutcome.REJECTED,
                    enriched.outcome().summary());
            return enriched.section();
        } catch (AgentExecutionStoppedException stopped) {
            if (stopped.reason() == AgentExecutionStoppedException.StopReason.CANCELLED) {
                throw stopped;
            }
            log.warn(
                    "Optional visual enrichment stopped for teaching topic {}; retaining cited text ({})",
                    planned.topicKey(),
                    stopped.reason());
            return section;
        } catch (RuntimeException visualFailure) {
            log.warn(
                    "Optional visual enrichment failed for teaching topic {}; publishing cited text: {}",
                    planned.topicKey(),
                    visualFailure.getMessage());
            return section;
        }
    }

    private void reEnrichAcceptedReplacements(
            TeachingPlan plan,
            TeachingPublishedLessonReviewer.ReviewResult reviewResult,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher) {
        for (int sectionIndex : reviewResult.acceptedReplacementIndexes()) {
            LessonSection replacement = sections.get(sectionIndex);
            LessonSection enriched = enrichValidatedSection(
                    plan,
                    plan.sections().get(sectionIndex),
                    replacement,
                    sections.subList(0, sectionIndex),
                    assistantRunId);
            sections.set(sectionIndex, enriched);
            progressPublisher.run();
        }
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
        private static final GenerationMode BASE = new GenerationMode(
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
            ActivityOutcome publicationOutcome,
            String publicationCategory) {
        private SectionOutcome withSection(LessonSection replacement) {
            return new SectionOutcome(
                    position,
                    planned,
                    replacement,
                    reviewCandidate,
                    publicationOutcome,
                    publicationCategory);
        }
    }

    WorkloadDemand workload(TeachingPlan plan) {
        return workloadPolicy.demand(plan);
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }
}
