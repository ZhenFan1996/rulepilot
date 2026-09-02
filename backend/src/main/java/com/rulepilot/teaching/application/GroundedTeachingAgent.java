package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.shared.AsyncContextPropagation;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GroundedTeachingAgent {

    private static final Logger log = LoggerFactory.getLogger(GroundedTeachingAgent.class);
    static final String GENERATOR_VERSION = "teaching-agent-v67-planned-visual-pages";
    private static final Set<String> REUSABLE_GENERATOR_VERSIONS =
            Set.of(GENERATOR_VERSION);
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final AuditedAgentInvocations invocations;
    private final TeachingSectionEvidenceRetriever evidenceRetriever;
    private final TeachingSectionDraftComposer sectionDraftComposer;
    private final TeachingBaseSectionPublicationPolicy basePublication =
            new TeachingBaseSectionPublicationPolicy();
    private final TeachingLessonAssemblyPolicy lessonAssembly = new TeachingLessonAssemblyPolicy();
    private final TeachingRunWorkloadPolicy workloadPolicy;
    private final VisualLessonEnricher visualEnricher;

    @Autowired
    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookCataloger visualCataloger,
            VisualLessonEnricher visualEnricher) {
        this.tools = tools;
        this.model = model;
        this.invocations = invocations;
        TeachingVisualEvidenceResolver visualEvidenceResolver = new TeachingVisualEvidenceResolver(
                tools, invocations, visualFacts, visualCataloger);
        this.evidenceRetriever = new TeachingSectionEvidenceRetriever(
                tools, evidenceVerifier, invocations, visualEvidenceResolver);
        this.sectionDraftComposer = new TeachingSectionDraftComposer(
                model, evidenceVerifier, invocations, visualFacts);
        this.workloadPolicy = new TeachingRunWorkloadPolicy();
        this.visualEnricher = visualEnricher;
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations,
            VisualRulebookPageFacts visualFacts,
            VisualRulebookCataloger visualCataloger) {
        this(
                tools,
                model,
                evidenceVerifier,
                invocations,
                visualFacts,
                visualCataloger,
                null);
    }

    /**
     * Publishes each cited text section and assembles its optional visual evidence before moving to the next section.
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
        UUID lessonId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Map<String, LessonSection> reusable = reusableSections(plan, previousLesson);
        BaseLessonContinuation continuation = new BaseLessonContinuation(
                plan,
                assistantRunId,
                lessonId,
                createdAt,
                reusable,
                new ArrayList<>(),
                AsyncContextPropagation.executorService(Executors.newVirtualThreadPerTaskExecutor()));
        scheduleSections(continuation);
        settleNextPublishedSection(continuation, progressPublisher);
        return continuation;
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
        List<LessonSection> sections = continuation.sections;
        if (continuation.hasRemainingWork()) {
            settleNextPublishedSection(continuation, progressPublisher);
            if (continuation.hasRemainingWork()) {
                return new BaseWorkUnitResult(lesson(lessonId, plan, sections, createdAt), false);
            }
        }
        return new BaseWorkUnitResult(lesson(lessonId, plan, sections, createdAt), true);
    }

    static final class BaseLessonContinuation {
        private final TeachingPlan plan;
        private final UUID assistantRunId;
        private final UUID lessonId;
        private final Instant createdAt;
        private final Map<String, LessonSection> reusableSections;
        private final List<LessonSection> sections;
        private final ExecutorService sectionTasks;
        private final BlockingQueue<SettledSection> settledSections = new LinkedBlockingQueue<>();
        private int unsettledSections;

        private BaseLessonContinuation(
                TeachingPlan plan,
                UUID assistantRunId,
                UUID lessonId,
                Instant createdAt,
                Map<String, LessonSection> reusableSections,
                List<LessonSection> sections,
                ExecutorService sectionTasks) {
            this.plan = plan;
            this.assistantRunId = assistantRunId;
            this.lessonId = lessonId;
            this.createdAt = createdAt;
            this.reusableSections = reusableSections;
            this.sections = sections;
            this.sectionTasks = sectionTasks;
        }

        boolean hasRemainingWork() {
            return unsettledSections > 0;
        }
    }

    private record SettledSection(
            TeachingPlan.PlannedSection planned,
            SectionOutcome outcome,
            Throwable failure) {}

    record BaseWorkUnitResult(IllustratedLesson lesson, boolean complete) {
        BaseWorkUnitResult {
            if (lesson == null) throw new IllegalArgumentException("lesson work unit result is required");
        }
    }

    private void scheduleSections(BaseLessonContinuation continuation) {
        Map<String, CompletableFuture<SectionOutcome>> byTopic = new java.util.LinkedHashMap<>();
        Map<String, List<String>> prerequisites = new java.util.LinkedHashMap<>();
        continuation.plan.wholeGameContext().topicDependencies().forEach(dependency -> prerequisites
                .computeIfAbsent(dependency.dependentTopicKey(), ignored -> new ArrayList<>())
                .add(dependency.prerequisiteTopicKey()));
        continuation.unsettledSections = continuation.plan.sections().size();
        for (TeachingPlan.PlannedSection planned : continuation.plan.sections()) {
            List<String> prerequisiteTopics = List.copyOf(
                    prerequisites.getOrDefault(planned.topicKey(), List.of()));
            List<CompletableFuture<SectionOutcome>> requiredFutures = prerequisiteTopics.stream()
                    .map(byTopic::get)
                    .toList();
            if (requiredFutures.contains(null)) {
                throw new IllegalArgumentException("teaching chapter dependency must reference an earlier chapter");
            }
            CompletableFuture<Void> ready = CompletableFuture.allOf(
                    requiredFutures.toArray(CompletableFuture[]::new));
            CompletableFuture<SectionOutcome> future = ready.thenApplyAsync(ignored -> {
                try {
                    List<LessonSection> prerequisiteSections = requiredFutures.stream()
                            .map(CompletableFuture::join)
                            .map(SectionOutcome::section)
                            .filter(java.util.Objects::nonNull)
                            .sorted(java.util.Comparator.comparingInt(LessonSection::position))
                            .toList();
                    SectionOutcome outcome = baseSection(
                            continuation.plan,
                            planned,
                            lessonAssembly.continuityContext(prerequisiteSections),
                            continuation.reusableSections,
                            continuation.assistantRunId);
                    if (outcome.section() == null) return outcome;
                    LessonSection enriched = enrichValidatedSection(
                            continuation.plan,
                            planned,
                            outcome.section(),
                            prerequisiteSections,
                            continuation.assistantRunId);
                    return outcome.withSection(enriched);
                } catch (AgentExecutionStoppedException hardStop) {
                    throw hardStop;
                } catch (RuntimeException localFailure) {
                    log.warn(
                            "Teaching chapter {} became locally unavailable; independent chapters continue: {}",
                            planned.topicKey(),
                            localFailure.getMessage());
                    return new SectionOutcome(
                            planned,
                            null,
                            ActivityOutcome.REJECTED,
                            "CHAPTER_LOCALLY_UNAVAILABLE");
                }
            }, continuation.sectionTasks);
            future.whenComplete((outcome, failure) -> continuation.settledSections.add(
                    new SettledSection(planned, outcome, failure)));
            byTopic.put(planned.topicKey(), future);
        }
        if (continuation.unsettledSections == 0) continuation.sectionTasks.shutdown();
    }

    /** Settles local failures as observations and publishes one fully prepared chapter snapshot at most. */
    private void settleNextPublishedSection(
            BaseLessonContinuation continuation,
            Consumer<IllustratedLesson> progressPublisher) {
        while (continuation.unsettledSections > 0) {
            SettledSection settled;
            try {
                settled = continuation.settledSections.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("teaching chapter settlement was interrupted", interrupted);
            }
            continuation.unsettledSections--;
            if (continuation.unsettledSections == 0) continuation.sectionTasks.shutdown();
            if (settled.failure() != null) {
                RuntimeException failure = propagateSectionFailure(settled.failure());
                if (failure instanceof AgentExecutionStoppedException) {
                    continuation.sectionTasks.shutdownNow();
                    throw failure;
                }
                log.warn(
                        "Teaching chapter {} failed outside its local task boundary; independent chapters continue: {}",
                        settled.planned().topicKey(),
                        failure.getMessage());
                recordPublication(
                        continuation.assistantRunId,
                        settled.planned(),
                        ActivityOutcome.REJECTED,
                        "CHAPTER_LOCALLY_UNAVAILABLE");
                continue;
            }
            SectionOutcome outcome = settled.outcome();
            recordPublication(
                    continuation.assistantRunId,
                    outcome.planned(),
                    outcome.publicationOutcome(),
                    outcome.publicationCategory());
            if (outcome.section() == null) continue;
            continuation.sections.add(outcome.section());
            continuation.sections.sort(java.util.Comparator.comparingInt(LessonSection::position));
            publishProgress(
                    progressPublisher,
                    continuation.lessonId,
                    continuation.plan,
                    continuation.sections,
                    continuation.createdAt);
            return;
        }
    }

    private RuntimeException propagateSectionFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        if (current instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("teaching chapter task failed", current);
    }

    private SectionOutcome baseSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId) {
        return generateSection(
                plan,
                planned,
                priorSections,
                reusableSections,
                assistantRunId,
                planned.position() - 1);
    }

    private SectionOutcome generateSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int sectionIndex) {
        LessonSection reusable = reusableSections.get(planned.topicKey());
        if (reusable != null) {
            return new SectionOutcome(
                    planned, reusable, ActivityOutcome.SUCCEEDED, "REUSED_VERIFIED_SECTION");
        }
        TeachingSectionEvidenceRetriever.Result resolution = evidenceRetriever.retrieve(
                plan, planned, assistantRunId, true);
        return composeResolvedSection(
                plan,
                planned,
                priorSections,
                assistantRunId,
                resolution,
                sectionIndex);
    }

    private SectionOutcome composeResolvedSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result resolution,
            int sectionIndex) {
        if (!resolution.verified()) {
            return new SectionOutcome(
                    planned,
                    null,
                    ActivityOutcome.REJECTED,
                    resolution.state() == TeachingSectionEvidenceRetriever.State.EMPTY
                            ? "NO_VALID_BASE_EVIDENCE"
                            : "BASE_EVIDENCE_IDENTITY_INVALID");
        }
        try {
            TeachingSectionDraftCandidate composed = sectionDraftComposer.compose(
                    plan,
                    planned,
                    priorSections,
                    resolution.evidence(),
                    assistantRunId,
                    sectionIndex);
            LessonSection published = basePublication.publish(composed);
            return new SectionOutcome(
                    planned, published, ActivityOutcome.SUCCEEDED, "SUPPORTED_SECTION_PUBLISHED");
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidDraft) {
            log.warn("Teaching section {} was withheld: {}", planned.topicKey(), invalidDraft.getMessage());
            return new SectionOutcome(
                    planned,
                    null,
                    ActivityOutcome.REJECTED,
                    "BASE_DRAFT_WITHHELD");
        }
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
                    planned,
                    section,
                    List.copyOf(alreadyPublished),
                    plan.createdBy(),
                    assistantRunId,
                    new VisualLessonEnricher.VisualProgressListener() {});
            invocations.record(
                    assistantRunId,
                    ActivityType.VALIDATION,
                    "enrichTeachingSectionVisual|" + planned.position(),
                    VisualLessonEnricher.isSuccessfulOutcome(enriched.outcome().outcome())
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

    private Map<String, LessonSection> reusableSections(
            TeachingPlan plan, IllustratedLesson previousLesson) {
        return lessonAssembly.reusableSections(
                plan,
                previousLesson,
                REUSABLE_GENERATOR_VERSIONS);
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

    private record SectionOutcome(
            TeachingPlan.PlannedSection planned,
            LessonSection section,
            ActivityOutcome publicationOutcome,
            String publicationCategory) {
        private SectionOutcome withSection(LessonSection replacement) {
            return new SectionOutcome(
                    planned,
                    replacement,
                    publicationOutcome,
                    publicationCategory);
        }
    }

    WorkloadDemand workload(TeachingPlan plan) {
        return workloadPolicy.demand(plan);
    }
}
