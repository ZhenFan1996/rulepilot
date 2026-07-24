package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
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
import java.util.stream.Collectors;
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
    private static final int EVIDENCE_PER_INTENT = 3;
    /*
     * The cited base lesson is published section by section.  Whole-lesson review improves it,
     * but must not turn a usable first read into an unbounded rewrite job.
     */
    private static final int MAX_POST_PUBLICATION_REVIEW_PASSES = 1;
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final EvidenceVerifier evidenceVerifier;
    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final TeachingVisualEvidenceResolver visualEvidenceResolver;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingLessonAssemblyPolicy lessonAssembly = new TeachingLessonAssemblyPolicy();
    private final TeachingReviewCorrectionPolicy reviewCorrectionPolicy = new TeachingReviewCorrectionPolicy();
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
        this.visualEvidenceResolver = new TeachingVisualEvidenceResolver(
                tools, invocations, visualFacts, visualCatalog);
        this.sectionDraftComposer = new TeachingSectionDraftComposer(
                model, evidenceVerifier, invocations, visualFacts);
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
        for (String query : TeachingEvidenceRetrievalPolicy.queries(planned, queryBudget)) {
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
        List<RuleEvidence> evidence = conflictingEvidence
                ? List.of()
                : TeachingEvidenceRetrievalPolicy.balancedEvidence(evidenceByIntent);
        if (bindVisualPageEvidence) {
            evidence = visualEvidenceResolver.resolve(plan, planned, evidence, assistantRunId);
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

    private List<RuleEvidence> retrieve(UUID documentVersionId, String topicKey, String query) {
        return List.copyOf(tools.searchRuleEvidence(new SearchRuleEvidence(
                        documentVersionId,
                        TeachingEvidenceRetrievalPolicy.focusedQuery(query),
                        EVIDENCE_PER_INTENT,
                        Set.of(),
                        null,
                        true,
                        true)));
    }

    private void reviewPublishedLesson(
            TeachingPlan plan,
            List<TeachingSectionDraftCandidate> candidates,
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
            List<TeachingSectionDraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher,
            int remainingPasses) {
        LessonReviewPlanner.LessonReviewBatch batch = LessonReviewPlanner.plan(candidates, assistantRunId);
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
        List<TeachingSectionDraftCandidate> correctedCandidates = new ArrayList<>();
        int factualCorrectionsStarted = 0;
        int scopeCorrectionsStarted = 0;
        for (TeachingSectionDraftCandidate candidate : candidates) {
            List<GeneratedContentCritic.Issue> issues = issuesBySection.getOrDefault(
                    candidate.sectionIndex(), List.of());
            sectionDraftComposer.recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    0,
                    issues.isEmpty() ? ActivityOutcome.SUCCEEDED : ActivityOutcome.REJECTED,
                    issues.isEmpty() ? "POST_PUBLICATION_REVIEW_ACCEPTED" : reviewCorrectionPolicy.criticDiagnostic(issues));
            TeachingReviewCorrectionPolicy.CorrectionKind correctionKind = reviewCorrectionPolicy.correctionKind(issues);
            boolean correctionBudgetExhausted = reviewCorrectionPolicy.correctionBudgetExhausted(
                    correctionKind, factualCorrectionsStarted, scopeCorrectionsStarted);
            if (!issues.isEmpty() && correctionBudgetExhausted) {
                log.info(
                        "Whole-lesson review defers {} correction for topic {} after its immediate budget",
                        correctionKind == TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE
                                ? "chapter-scope"
                                : "factual",
                        candidate.planned().topicKey());
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        "POST_PUBLICATION_REVIEW_DEFERRED_FOR_INCREMENTAL_REVIEW");
                continue;
            }
            try {
                TeachingSectionDraftCandidate reviewed = issues.isEmpty()
                        ? new TeachingSectionDraftCandidate(
                                candidate.sectionIndex(),
                                candidate.planned(),
                                candidate.evidence(),
                                candidate.modelRequest(),
                                candidate.draft(),
                                sectionDraftComposer.validatedSection(
                                plan,
                                candidate.planned(),
                                candidate.evidence(),
                                candidate.modelRequest(),
                                candidate.draft(),
                                EvidenceStatus.SUPPORTED))
                        : correctedPublishedDraft(plan, candidate, issues, assistantRunId);
                if (!issues.isEmpty()) {
                    if (correctionKind == TeachingReviewCorrectionPolicy.CorrectionKind.CHAPTER_SCOPE) {
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

    private TeachingSectionDraftCandidate correctedPublishedDraft(
            TeachingPlan plan,
            TeachingSectionDraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues,
            UUID assistantRunId) {
        List<String> feedback = reviewCorrectionPolicy.correctionFeedback(issues);
        SectionDraft corrected = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("correctTeachingSection", candidate.planned().position()),
                estimateTokens(candidate.modelRequest().toString()) + estimateTokens(candidate.draft().toString())
                        + estimateTokens(feedback.toString()),
                "Published teaching section corrected from whole-lesson review",
                () -> model.revise(candidate.modelRequest(), candidate.draft(), feedback),
                result -> estimateTokens(result.toString()));
        corrected = sectionDraftComposer.normalizeDraft(corrected, candidate.modelRequest());
        EvidenceStatus correctionStatus = corrected.equals(candidate.draft())
                ? EvidenceStatus.CITED_DRAFT
                : EvidenceStatus.SUPPORTED;
        LessonSection correctedSection;
        try {
            correctedSection = sectionDraftComposer.validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        } catch (IllegalArgumentException invalidCorrection) {
            SectionDraft invalidDraft = corrected;
            List<String> structuralRepair = reviewCorrectionPolicy.structuralRepairFeedback(
                    feedback, TeachingDraftRejectionCategory.from(invalidCorrection));
            corrected = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    operationName("repairCorrectedTeachingSection", candidate.planned().position()),
                    estimateTokens(candidate.modelRequest().toString()) + estimateTokens(invalidDraft.toString())
                            + estimateTokens(structuralRepair.toString()),
                    "Published teaching correction repaired to the section contract",
                    () -> model.revise(candidate.modelRequest(), invalidDraft, structuralRepair),
                    result -> estimateTokens(result.toString()));
            corrected = sectionDraftComposer.normalizeDraft(corrected, candidate.modelRequest());
            correctionStatus = corrected.equals(candidate.draft()) ? EvidenceStatus.CITED_DRAFT : EvidenceStatus.SUPPORTED;
            correctedSection = sectionDraftComposer.validatedSection(
                    plan,
                    candidate.planned(),
                    candidate.evidence(),
                    candidate.modelRequest(),
                    corrected,
                    correctionStatus);
        }
        sectionDraftComposer.recordValidation(
                assistantRunId,
                candidate.planned(),
                1,
                ActivityOutcome.SUCCEEDED,
                "POST_PUBLICATION_CORRECTION_APPLIED");
        return new TeachingSectionDraftCandidate(
                candidate.sectionIndex(),
                candidate.planned(),
                candidate.evidence(),
                candidate.modelRequest(),
                corrected,
                correctedSection);
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

    private enum EvidenceState { VERIFIED, EMPTY, INVALID }

    private record EvidenceResolution(List<RuleEvidence> evidence, int toolCalls, EvidenceState state) {
        boolean verified() {
            return state == EvidenceState.VERIFIED;
        }
    }

    private record SectionOutcome(
            int position,
            LessonSection section,
            TeachingSectionDraftCandidate reviewCandidate,
            int retrievalToolCalls) {}

    private boolean sameEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo();
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(), evidence.documentVersionId(), evidence.sectionType(), evidence.excerpt(),
                evidence.pageFrom(), evidence.pageTo());
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
