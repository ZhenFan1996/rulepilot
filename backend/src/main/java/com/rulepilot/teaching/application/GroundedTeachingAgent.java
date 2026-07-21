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
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
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
    static final String GENERATOR_VERSION = "adaptive-teaching-v21-vision-observations";
    private static final Set<String> REUSABLE_GENERATOR_VERSIONS =
            Set.of(GENERATOR_VERSION);
    private static final int MAX_EVIDENCE_PER_SECTION = 10;
    private static final int EVIDENCE_PER_INTENT = 3;
    private static final int MAX_STEPS_PER_SECTION = 6;
    private static final int MAX_DRAFT_REPAIR_ATTEMPTS = 3;
    private static final int MAX_REVIEW_UNCITED_EVIDENCE_PER_SECTION = 2;
    private static final int MAX_VISUAL_FOCUS_AREA = 720_000;
    private static final String VISUAL_PAGE_PLACEHOLDER =
            "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
    private static final Pattern UNRESOLVED_PDF_MARKER = Pattern.compile("\\[[A-Za-z][A-Za-z _-]{0,30}]");
    private static final Pattern UNRESOLVED_EMOJI_ICON = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
    private static final Pattern TRAILING_INCOMPLETE_THOUGHT = Pattern.compile(
            "(?:…+|\\.\\.\\.)\\s*(?:完成(?:了)?|结束(?:了)?|等等|后续|其余)?[。！？!?]?\\s*$");
    private static final Pattern TEXT_ONLY_PRESENTATION_MARKER = Pattern.compile(
            "(?i)(attached|attachment|image|rulebook|page\\s*\\d|图片|附件|规则书|第\\s*\\d+\\s*页|页面)");
    private static final Pattern INTERNAL_EVIDENCE_MARKER = Pattern.compile(
            "(?i)(已提供的证据|提供的证据|当前证据|现有证据|证据中(?:没有|未|并未|不)|检索(?:结果|内容|证据)|"
                    + "retriev(?:al|ed)|(?:provided|supplied|current) evidence|evidence (?:does not|doesn't|did not))");
    private static final Pattern INTERNAL_SHORT_EVIDENCE_REFERENCE = Pattern.compile("(?<![\\p{L}\\p{N}])E\\d{1,2}(?![\\p{L}\\p{N}])");
    private static final Pattern RETRIEVAL_QUERY_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}'’-]+");
    private static final Set<String> ENGLISH_RETRIEVAL_FILLER = Set.of(
            "a", "an", "and", "are", "do", "does", "for", "how", "is", "of", "the", "to", "what", "when",
            "with", "you", "your");
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final EvidenceVerifier evidenceVerifier;
    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final int maxToolCalls;
    private final int baseSectionParallelism;

    @Autowired
    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.teaching.agent.max-tool-calls:72}") int maxToolCalls,
            @Value("${rulepilot.teaching.base-section-parallelism:3}") int baseSectionParallelism) {
        this.tools = tools;
        this.model = model;
        this.evidenceVerifier = evidenceVerifier;
        this.critic = critic;
        this.invocations = invocations;
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.baseSectionParallelism = Math.max(1, Math.min(6, baseSectionParallelism));
    }

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            int maxToolCalls) {
        this(tools, model, evidenceVerifier, critic, invocations, maxToolCalls, 3);
    }

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
        return create(plan, assistantRunId, previousLesson, progressPublisher, true, true);
    }

    /**
     * Publishes the lesson incrementally. Text-backed sections stay fast; image-only rulebooks
     * use their cited pages as primary evidence instead of guessing from placeholder text.
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
        TeachingPlan.PlannedSection first = plan.sections().getFirst();
        sections.add(baseSection(
                plan,
                first,
                pacing.get(first.position()),
                List.of(),
                reusable,
                assistantRunId,
                queriesPerTopic,
                false));
        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);

        List<TeachingPlan.PlannedSection> remaining = plan.sections().subList(1, plan.sections().size());
        if (!remaining.isEmpty()) {
            List<PriorSectionContext> sharedContext = continuityContext(sections);
            Map<Integer, LessonSection> completed = new LinkedHashMap<>();
            try (var executor = Executors.newFixedThreadPool(Math.min(baseSectionParallelism, remaining.size()))) {
                List<Future<SectionOutcome>> futures = remaining.stream()
                        .map(planned -> executor.submit(() -> {
                            LessonSection section = baseSection(
                                    plan,
                                    planned,
                                    pacing.get(planned.position()),
                                    sharedContext,
                                    reusable,
                                    assistantRunId,
                                    queriesPerTopic,
                                    false);
                            return new SectionOutcome(planned.position(), section);
                        }))
                        .toList();
                for (Future<SectionOutcome> future : futures) {
                    SectionOutcome outcome = await(future);
                    completed.put(outcome.position(), outcome.section());
                    while (completed.containsKey(sections.size() + 1)) {
                        sections.add(completed.remove(sections.size() + 1));
                        publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                    }
                }
            }
        }
        return lesson(lessonId, plan, sections, createdAt);
    }

    private LessonSection baseSection(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            Map<String, LessonSection> reusableSections,
            UUID assistantRunId,
            int queriesPerTopic,
            boolean includeVisualEvidence) {
        LessonSection reusable = reusableSections.get(planned.topicKey());
        if (reusable != null) {
            recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "REUSED_VERIFIED_SECTION");
            return reusable;
        }
        Map<UUID, RuleEvidence> evidenceById = new LinkedHashMap<>();
        List<List<RuleEvidence>> evidenceByIntent = new ArrayList<>();
        boolean conflictingEvidence = false;
        for (String query : retrievalQueries(planned, queriesPerTopic)) {
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
                log.warn("Base teaching retrieval failed for topic {}: {}", planned.topicKey(), retrievalFailure.getMessage());
            }
            if (conflictingEvidence) break;
        }
        List<RuleEvidence> evidence = conflictingEvidence ? List.of() : balancedEvidence(evidenceByIntent);
        if (evidence.isEmpty() || !evidenceVerifier.verify(new VerificationRequest(
                        plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                .verified()) {
            recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "NO_VALID_BASE_EVIDENCE");
            return insufficient(planned);
        }
        try {
            DraftCandidate composed = composeDraft(
                    plan,
                    planned,
                    pacing,
                    priorSections,
                    evidence,
                    assistantRunId,
                    planned.position() - 1,
                    includeVisualEvidence);
            recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "CITED_BASE_SECTION_PUBLISHED");
            return composed.section();
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException invalidDraft) {
            log.warn("Base teaching section {} was withheld: {}", planned.topicKey(), invalidDraft.getMessage());
            recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "BASE_DRAFT_WITHHELD");
            return insufficient(planned);
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

    private IllustratedLesson create(
            TeachingPlan plan,
            UUID assistantRunId,
            IllustratedLesson previousLesson,
            Consumer<IllustratedLesson> progressPublisher,
            boolean includeVisualEvidence,
            boolean reviewBeforeReturn) {
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
            LessonSection reusable = reusableSections.get(planned.topicKey());
            if (reusable != null) {
                log.info("Teaching topic {} reuses a previously verified section", planned.topicKey());
                sections.add(reusable);
                recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "REUSED_VERIFIED_SECTION");
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }
            if (toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before topic {}", planned.topicKey());
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "TOOL_BUDGET_EXHAUSTED");
                sections.add(insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }

            Map<UUID, RuleEvidence> evidenceById = new LinkedHashMap<>();
            List<List<RuleEvidence>> evidenceByIntent = new ArrayList<>();
            boolean conflictingEvidence = false;
            for (String query : retrievalQueries(planned, queriesPerTopic)) {
                if (toolCalls >= maxToolCalls) {
                    break;
                }
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
                    log.warn(
                            "Teaching Agent retrieval failed for topic {} query '{}': {}",
                            planned.topicKey(),
                            query,
                            retrievalFailure.getMessage());
                }
                if (conflictingEvidence) {
                    log.warn("Teaching Agent retrieved conflicting snapshots for topic {}", planned.topicKey());
                    break;
                }
            }
            List<RuleEvidence> evidence = conflictingEvidence
                    ? List.of()
                    : balancedEvidence(evidenceByIntent);
            if (evidence.isEmpty()) {
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "NO_RETRIEVED_EVIDENCE");
                sections.add(insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }
            if (!evidenceVerifier.verify(new VerificationRequest(
                            plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                    .verified()) {
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "RETRIEVED_EVIDENCE_INVALID");
                sections.add(insufficient(planned));
                publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
                continue;
            }

            try {
                DraftCandidate composed = composeDraft(
                        plan,
                        planned,
                        pacing.get(planned.position()),
                        continuityContext(sections),
                        evidence,
                        assistantRunId,
                        sections.size(),
                        includeVisualEvidence);
                sections.add(composed.section());
                reviewCandidates.add(composed);
                recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "CITED_DRAFT_PUBLISHED");
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException invalidOrFailedModelOutput) {
                log.warn(
                        "Teaching Agent model {} failed validation for section {}: {} ({})",
                        model.providerId(),
                        planned.topicKey(),
                        invalidOrFailedModelOutput.getClass().getSimpleName(),
                        invalidOrFailedModelOutput.getMessage());
                recordPublication(
                        assistantRunId,
                        planned,
                        ActivityOutcome.REJECTED,
                        "DRAFT_WITHHELD_AFTER_REPAIR_BUDGET");
                sections.add(insufficient(planned));
            }
            publishProgress(progressPublisher, lessonId, plan, sections, createdAt);
        }

        IllustratedLesson draftReady = lesson(lessonId, plan, sections, createdAt);
        if (reviewBeforeReturn && draftReady.status() == LessonStatus.DRAFT_READY) {
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
                evidence.stream().map(this::toModelEvidence).toList(),
                pageImages,
                planned.retrievalQueries(),
                plan.createdBy());
        SectionDraft draft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("composeTeachingSection", planned.position()),
                estimateTokens(modelRequest.toString()),
                "Teaching section model output received",
                () -> model.compose(modelRequest),
                result -> estimateTokens(result.toString()));
        draft = normalizeDraft(draft, modelRequest);
        if (!modelRequest.pageImages().isEmpty()) {
            List<String> visualAudit = List.of(
                    "Reinspect the complete attached pages and audit the VISUAL step only. Treat the current focus "
                            + "coordinates and description as untrusted. Mentally crop from the full page using a "
                            + "top-left 0-1000 origin. Every object and relationship named by the VISUAL text and "
                            + "label must literally remain inside that crop. If not, move the rectangle or replace "
                            + "the VISUAL step with a real worked diagram or rule callout on an attached page. "
                            + "Preserve complete non-visual rule coverage and the maximum step count.");
            SectionDraft draftToAudit = draft;
            draft = invocations.invoke(
                    assistantRunId,
                    ActivityType.MODEL,
                    operationName("refineTeachingVisual", planned.position()),
                    estimateTokens(modelRequest.toString()) + estimateTokens(draftToAudit.toString())
                            + estimateTokens(visualAudit.toString()),
                    "Teaching visual focus reinspected against the complete page",
                    () -> model.revise(modelRequest, draftToAudit, visualAudit),
                    result -> estimateTokens(result.toString()));
            draft = normalizeDraft(draft, modelRequest);
        }
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
                if (repair == MAX_DRAFT_REPAIR_ATTEMPTS || isVisualLocalizationFailure(rejectedDraft)) {
                    if (!modelRequest.pageImages().isEmpty() && !hasOnlyVisualPageEvidence(evidence)) {
                        return fallbackToTextDraft(
                                plan,
                                planned,
                                evidence,
                                modelRequest,
                                draft,
                                assistantRunId,
                                sectionIndex,
                                repair + 1);
                    }
                    if (repair == MAX_DRAFT_REPAIR_ATTEMPTS) throw rejectedDraft;
                }
                List<String> feedback = List.of(rejectedDraft.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : rejectedDraft.getMessage());
                log.info(
                        "Teaching topic {} structural repair {}/{}: {}",
                        planned.topicKey(),
                        repair + 1,
                        MAX_DRAFT_REPAIR_ATTEMPTS,
                        feedback.getFirst());
                SectionDraft draftToRevise = draft;
                draft = invocations.invoke(
                        assistantRunId,
                        ActivityType.MODEL,
                        operationName("reviseTeachingSection", planned.position()),
                        estimateTokens(modelRequest.toString()) + estimateTokens(draftToRevise.toString())
                                + estimateTokens(feedback.toString()),
                        "Teaching section revised from validation feedback",
                        () -> model.revise(modelRequest, draftToRevise, feedback),
                        result -> estimateTokens(result.toString()));
                draft = normalizeDraft(draft, modelRequest);
            }
        }
    }

    private DraftCandidate fallbackToTextDraft(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            TeachingLessonModel.SectionRequest visualRequest,
            SectionDraft visualDraft,
            UUID assistantRunId,
            int sectionIndex,
            int validationAttempt) {
        TeachingLessonModel.SectionRequest textOnlyRequest = withoutPageImages(visualRequest);
        List<String> feedback = List.of(
                "Visual localization could not be validated. Preserve complete grounded rule coverage, "
                        + "but return a text-only section with no VISUAL step, page mention, or visualFocus.");
        SectionDraft textOnlyDraft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("fallbackToTextTeachingSection", planned.position()),
                estimateTokens(textOnlyRequest.toString()) + estimateTokens(visualDraft.toString())
                        + estimateTokens(feedback.toString()),
                "Visual teaching section fell back to complete grounded text",
                () -> model.revise(textOnlyRequest, visualDraft, feedback),
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
            }
        }
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
                request.modelConfigurationOwner());
    }

    private SectionDraft normalizeDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        SectionDraft normalized = normalizePresentationMetadata(draft, request.pageImages().isEmpty());
        normalized = alignVisualStepsWithPageEvidence(normalized, request);
        return alignVisualCaptionWithStep(normalized, request.pageImages().isEmpty());
    }

    private SectionDraft alignVisualStepsWithPageEvidence(
            SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null || request.pageImages().isEmpty()) return draft;
        Set<Integer> attachedPages = request.pageImages().stream()
                .map(TeachingLessonModel.PageImageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        Map<UUID, TeachingLessonModel.EvidenceInput> evidenceById = request.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(TeachingLessonModel.EvidenceInput::chunkId, Function.identity()));
        boolean changed = false;
        List<StepDraft> steps = new ArrayList<>(draft.steps().size());
        for (StepDraft step : draft.steps()) {
            if (step == null || step.kind() != TeachingMove.VISUAL) {
                steps.add(step);
                continue;
            }
            boolean alreadyPageBacked = step.citationIds().stream()
                    .map(evidenceById::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(attachedPages::contains));
            if (alreadyPageBacked) {
                steps.add(step);
                continue;
            }
            UUID pageEvidence = request.evidence().stream()
                    .filter(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(attachedPages::contains))
                    .map(TeachingLessonModel.EvidenceInput::chunkId)
                    .findFirst()
                    .orElse(null);
            if (pageEvidence == null) {
                steps.add(step);
                continue;
            }
            List<UUID> citations = new ArrayList<>(step.citationIds());
            citations.add(pageEvidence);
            steps.add(new StepDraft(
                    step.heading(), step.kind(), step.text(), List.copyOf(new LinkedHashSet<>(citations)), step.visualFocus()));
            changed = true;
        }
        return changed
                ? new SectionDraft(
                        draft.title(), draft.visualKind(), draft.visualCaption(), draft.visualCitationIds(), steps)
                : draft;
    }

    private SectionDraft alignVisualCaptionWithStep(SectionDraft draft, boolean textOnly) {
        if (draft == null || textOnly) return draft;
        StepDraft visualStep = draft.steps().stream()
                .filter(step -> step != null && step.kind() == TeachingMove.VISUAL)
                .findFirst()
                .orElse(null);
        if (visualStep == null) return draft;
        String caption = visualStep.text().length() <= 240 ? visualStep.text() : visualStep.heading();
        return new SectionDraft(
                draft.title(), draft.visualKind(), caption, visualStep.citationIds(), draft.steps());
    }

    private SectionDraft normalizePresentationMetadata(SectionDraft draft, boolean textOnly) {
        if (draft == null || draft.steps() == null) return draft;
        StepDraft anchor = draft.steps().stream()
                .filter(step -> step != null
                        && step.heading() != null && !step.heading().isBlank()
                        && step.text() != null && !step.text().isBlank()
                        && step.citationIds() != null && !step.citationIds().isEmpty())
                .findFirst()
                .orElse(null);
        if (anchor == null) return draft;

        String caption = draft.visualCaption();
        if (caption == null || caption.isBlank() || caption.length() > 240
                || textOnly && TEXT_ONLY_PRESENTATION_MARKER.matcher(caption).find()) {
            caption = anchor.text().length() <= 240 ? anchor.text() : anchor.heading();
        }
        List<UUID> visualCitations = draft.visualCitationIds();
        if (visualCitations == null || visualCitations.isEmpty()) {
            visualCitations = anchor.citationIds();
        }
        if (caption.equals(draft.visualCaption()) && visualCitations.equals(draft.visualCitationIds())) {
            return draft;
        }
        return new SectionDraft(draft.title(), draft.visualKind(), caption, visualCitations, draft.steps());
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
        validateDraft(draft, modelRequest);

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        validateVisualBlockEvidence(draft, modelRequest, allowedEvidence);
        List<UUID> visualCitationIds = validatedVisualCitationIds(draft, allowedEvidence);
        List<Claim> reviewClaims = reviewClaims(draft, visualCitationIds);
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
                .mapToObj(index -> validatedStep(index + 1, draft.steps().get(index), allowedEvidence))
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
        reviewPublishedBatch(plan, candidates, sections, assistantRunId, progressPublisher);
    }

    private boolean reviewPublishedBatch(
            TeachingPlan plan,
            List<DraftCandidate> candidates,
            List<LessonSection> sections,
            UUID assistantRunId,
            Runnable progressPublisher) {
        LessonReviewBatch batch = lessonReviewBatch(candidates, assistantRunId);
        GeneratedContentCritic.Review review;
        try {
            review = critic.review(batch.request(), ReviewRisk.HIGH_IMPACT);
        } catch (AgentExecutionStoppedException stopped) {
            candidates.forEach(candidate -> recordPublication(
                    assistantRunId,
                    candidate.planned(),
                    ActivityOutcome.REJECTED,
                    "POST_PUBLICATION_REVIEW_DEFERRED_BY_BUDGET"));
            return false;
        } catch (RuntimeException reviewFailure) {
            log.warn("Whole-lesson objective coverage review retained cited draft: {}", reviewFailure.getMessage());
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
        for (DraftCandidate candidate : candidates) {
            List<GeneratedContentCritic.Issue> issues = issuesBySection.getOrDefault(
                    candidate.sectionIndex(), List.of());
            recordValidation(
                    assistantRunId,
                    candidate.planned(),
                    0,
                    issues.isEmpty() ? ActivityOutcome.SUCCEEDED : ActivityOutcome.REJECTED,
                    issues.isEmpty() ? "POST_PUBLICATION_REVIEW_ACCEPTED" : criticDiagnostic(issues));
            try {
                LessonSection reviewed = issues.isEmpty()
                        ? validatedSection(
                                plan,
                                candidate.planned(),
                                candidate.evidence(),
                                candidate.modelRequest(),
                                candidate.draft(),
                                EvidenceStatus.SUPPORTED)
                        : correctedPublishedDraft(plan, candidate, issues, assistantRunId);
                sections.set(candidate.sectionIndex(), reviewed);
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.SUCCEEDED,
                        reviewed.evidenceStatus() == EvidenceStatus.SUPPORTED
                                ? "POST_PUBLICATION_REVIEW_ACCEPTED"
                                : "POST_PUBLICATION_REVIEW_PENDING");
                progressPublisher.run();
            } catch (AgentExecutionStoppedException stopped) {
                recordPublication(
                        assistantRunId,
                        candidate.planned(),
                        ActivityOutcome.REJECTED,
                        "POST_PUBLICATION_REVIEW_DEFERRED_BY_BUDGET");
                return false;
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
        return true;
    }

    private LessonReviewBatch lessonReviewBatch(List<DraftCandidate> candidates, UUID assistantRunId) {
        List<Claim> claims = new ArrayList<>();
        Map<Integer, DraftCandidate> claimOwners = new LinkedHashMap<>();
        Map<UUID, RuleEvidence> evidence = new LinkedHashMap<>();
        for (DraftCandidate candidate : candidates) {
            reviewEvidence(candidate).forEach(source -> evidence.putIfAbsent(source.chunkId(), source));
            List<UUID> visualCitationIds = validatedVisualCitationIds(
                    candidate.draft(),
                    candidate.evidence().stream().collect(Collectors.toUnmodifiableMap(
                            RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first)));
            for (Claim claim : reviewClaims(candidate.draft(), visualCitationIds)) {
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
        ReviewRequest request = new ReviewRequest(
                assistantRunId,
                ContentType.LESSON,
                ReviewMode.OBJECTIVE_COVERAGE,
                new TaskContext(objective, requiredCoverage),
                claims,
                evidence.values().stream()
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
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

    private LessonSection correctedPublishedDraft(
            TeachingPlan plan,
            DraftCandidate candidate,
            List<GeneratedContentCritic.Issue> issues,
            UUID assistantRunId) {
        List<String> feedback = List.of("Whole-lesson objective coverage review found: " + issues.stream()
                .map(issue -> issue.type() + " evidence=" + issue.evidenceIds() + " - " + issue.summary())
                .collect(Collectors.joining("; ")));
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
        LessonSection correctedDraft = validatedSection(
                plan,
                candidate.planned(),
                candidate.evidence(),
                candidate.modelRequest(),
                corrected,
                EvidenceStatus.CITED_DRAFT);
        recordValidation(
                assistantRunId,
                candidate.planned(),
                1,
                ActivityOutcome.SUCCEEDED,
                "POST_PUBLICATION_CORRECTION_APPLIED");
        return correctedDraft;
    }

    private String requiredCoverage(TeachingPlan.PlannedSection planned) {
        return "Coverage tags: " + String.join(", ", planned.coverageTags())
                + "; required retrieval intents: " + String.join("; ", planned.retrievalQueries());
    }

    private List<Claim> reviewClaims(SectionDraft draft, List<UUID> visualCitationIds) {
        boolean captionDuplicatesVisualStep = draft.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL)
                .anyMatch(step -> step.text().equals(draft.visualCaption())
                        && Set.copyOf(step.citationIds()).equals(Set.copyOf(visualCitationIds)));
        List<Claim> claims = new ArrayList<>();
        if (!captionDuplicatesVisualStep) {
            claims.add(new Claim(1, draft.visualCaption(), visualCitationIds));
        }
        int firstStepPosition = claims.size() + 1;
        IntStream.range(0, draft.steps().size())
                .mapToObj(index -> new Claim(
                        firstStepPosition + index,
                        draft.steps().get(index).heading() + "：" + draft.steps().get(index).text(),
                        draft.steps().get(index).citationIds()))
                .forEach(claims::add);
        return List.copyOf(claims);
    }

    private List<UUID> validatedVisualCitationIds(
            SectionDraft draft,
            Map<UUID, RuleEvidence> allowedEvidence) {
        LinkedHashSet<UUID> citationIds = new LinkedHashSet<>(draft.visualCitationIds());
        if (citationIds.isEmpty() || citationIds.contains(null)
                || !allowedEvidence.keySet().containsAll(citationIds)) {
            throw new IllegalArgumentException("teaching visual cites evidence outside retrieval scope");
        }
        return List.copyOf(citationIds);
    }

    private void validateVisualBlockEvidence(
            SectionDraft draft,
            TeachingLessonModel.SectionRequest request,
            Map<UUID, RuleEvidence> allowedEvidence) {
        Set<Integer> attachedPages = request.pageImages().stream()
                .map(TeachingLessonModel.PageImageInput::pageNumber)
                .collect(Collectors.toUnmodifiableSet());
        for (TeachingLessonModel.StepDraft step : draft.steps()) {
            if (step.kind() != TeachingMove.VISUAL) continue;
            VisualFocusDraft focus = step.visualFocus();
            if (focus == null || !attachedPages.contains(focus.pageNumber())) {
                throw new IllegalArgumentException(
                        "VISUAL teaching blocks must identify a focus region on an attached rulebook page.");
            }
            validatedFocus(focus);
            boolean citesAttachedPage = step.citationIds().stream()
                    .map(allowedEvidence::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                            .anyMatch(attachedPages::contains));
            if (!citesAttachedPage) {
                throw new IllegalArgumentException(
                        "VISUAL teaching blocks must cite evidence from an attached rulebook page.");
            }
        }
    }

    private LessonStep validatedStep(
            int position,
            TeachingLessonModel.StepDraft draft,
            Map<UUID, RuleEvidence> allowedEvidence) {
        if (draft == null || draft.text() == null || draft.text().isBlank() || draft.text().length() > 600
                || draft.citationIds().isEmpty()) {
            throw new IllegalArgumentException("teaching step is invalid");
        }
        LinkedHashSet<UUID> citationIds = new LinkedHashSet<>(draft.citationIds());
        if (citationIds.contains(null) || !allowedEvidence.keySet().containsAll(citationIds)) {
            throw new IllegalArgumentException("teaching step cites evidence outside retrieval scope");
        }
        List<Integer> pages = citationIds.stream()
                .map(allowedEvidence::get)
                .flatMapToInt(source -> IntStream.rangeClosed(source.pageFrom(), source.pageTo()))
                .distinct()
                .sorted()
                .boxed()
                .toList();
        return new LessonStep(
                position,
                draft.heading().strip(),
                draft.kind(),
                draft.text().strip(),
                pages,
                List.copyOf(citationIds),
                validatedVisualFocus(draft));
    }

    private VisualFocus validatedVisualFocus(TeachingLessonModel.StepDraft draft) {
        VisualFocusDraft focus = draft.visualFocus();
        if (draft.kind() != TeachingMove.VISUAL) {
            if (focus != null) {
                throw new IllegalArgumentException("Only VISUAL teaching blocks may define a visual focus.");
            }
            return null;
        }
        if (focus == null) {
            throw new IllegalArgumentException("VISUAL teaching blocks require a visual focus.");
        }
        return validatedFocus(focus);
    }

    private VisualFocus validatedFocus(VisualFocusDraft focus) {
        int x = Math.max(0, Math.min(980, focus.x()));
        int y = Math.max(0, Math.min(980, focus.y()));
        int width = Math.max(20, Math.min(focus.width(), 1_000 - x));
        int height = Math.max(20, Math.min(focus.height(), 1_000 - y));
        if ((long) width * height > MAX_VISUAL_FOCUS_AREA) {
            throw new IllegalArgumentException(
                    "VISUAL teaching blocks require a tight focus region, not an almost complete rulebook page.");
        }
        return new VisualFocus(
                focus.pageNumber(), focus.label(), x, y, width, height);
    }

    private void validateDraft(SectionDraft draft, TeachingLessonModel.SectionRequest request) {
        if (draft == null) throw new IllegalArgumentException("The draft is missing.");
        if (draft.title() == null || draft.title().isBlank() || draft.title().length() > 160)
            throw new IllegalArgumentException("The title is missing or longer than 160 characters.");
        if (draft.visualKind() == null) throw new IllegalArgumentException("visualKind is missing.");
        if (draft.visualCaption() == null || draft.visualCaption().isBlank())
            throw new IllegalArgumentException("The visual caption is missing.");
        if (draft.visualCaption().length() > 240)
            throw new IllegalArgumentException("The visual caption is longer than 240 characters.");
        if (draft.visualCitationIds().isEmpty())
            throw new IllegalArgumentException("The visual caption has no evidence citation.");
        if (draft.steps().isEmpty() || draft.steps().size() > Math.min(MAX_STEPS_PER_SECTION, request.maxSteps()))
            throw new IllegalArgumentException("The draft must contain between 1 and "
                    + Math.min(MAX_STEPS_PER_SECTION, request.maxSteps()) + " steps.");
        if (draft.steps().stream().anyMatch(step -> step == null
                || step.heading() == null || step.heading().isBlank() || step.heading().length() > 32
                || step.kind() == null)) {
            throw new IllegalArgumentException("Every step needs a short heading and a teaching kind.");
        }
        if (request.pageImages().isEmpty()
                && draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL)) {
            throw new IllegalArgumentException("VISUAL teaching blocks require attached rulebook page evidence.");
        }
        if (!request.pageImages().isEmpty()
                && draft.steps().stream().noneMatch(step -> step.kind() == TeachingMove.VISUAL)) {
            throw new IllegalArgumentException(
                    "Attached rulebook pages were selected because this topic needs visual teaching. "
                            + "Replace one suitable step with a VISUAL step that tells the player what to locate and "
                            + "includes a tight visualFocus rectangle on an attached, cited page.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.VISUAL
                && step.visualFocus() == null)) {
            throw new IllegalArgumentException("VISUAL teaching blocks require a visual focus region.");
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() != TeachingMove.VISUAL
                && step.visualFocus() != null)) {
            throw new IllegalArgumentException("Only VISUAL teaching blocks may define a visual focus region.");
        }
        if (UNRESOLVED_PDF_MARKER.matcher(draft.visualCaption()).find()
                || draft.steps().stream().anyMatch(step -> step != null && step.text() != null
                        && UNRESOLVED_PDF_MARKER.matcher(step.text()).find())) {
            throw new IllegalArgumentException(
                    "Replace unresolved PDF icon markers with natural Simplified Chinese terms.");
        }
        if (UNRESOLVED_EMOJI_ICON.matcher(draft.visualCaption()).find()
                || draft.steps().stream().anyMatch(step -> step != null && step.text() != null
                        && UNRESOLVED_EMOJI_ICON.matcher(step.text()).find())) {
            throw new IllegalArgumentException(
                    "Replace inferred emoji icons with an evidenced natural-language rule term.");
        }
        if (draft.steps().stream().anyMatch(step -> step != null
                && step.text() != null
                && TRAILING_INCOMPLETE_THOUGHT.matcher(step.text()).find())) {
            throw new IllegalArgumentException(
                    "Finish every player-facing step; do not end a rule, example, or calculation with an ellipsis.");
        }
        if (INTERNAL_EVIDENCE_MARKER.matcher(draft.visualCaption()).find()
                || draft.steps().stream().anyMatch(step -> step != null
                        && ((step.heading() != null && INTERNAL_EVIDENCE_MARKER.matcher(step.heading()).find())
                                || (step.text() != null && INTERNAL_EVIDENCE_MARKER.matcher(step.text()).find())))) {
            throw new IllegalArgumentException(
                    "Remove internal evidence or retrieval language and teach the player-facing rule directly.");
        }
        if (INTERNAL_SHORT_EVIDENCE_REFERENCE.matcher(draft.visualCaption()).find()
                || draft.steps().stream().anyMatch(step -> step != null
                        && ((step.heading() != null && INTERNAL_SHORT_EVIDENCE_REFERENCE.matcher(step.heading()).find())
                                || (step.text() != null
                                        && INTERNAL_SHORT_EVIDENCE_REFERENCE.matcher(step.text()).find())))) {
            throw new IllegalArgumentException(
                    "Remove internal short evidence references such as E1 from player-facing teaching text.");
        }
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
        if (message.contains("internal evidence or retrieval language")) return "INTERNAL_EVIDENCE_LANGUAGE";
        if (message.contains("internal short evidence references")) return "INTERNAL_EVIDENCE_REFERENCE";
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

    private record SectionOutcome(int position, LessonSection section) {}

    private record LessonReviewBatch(
            ReviewRequest request,
            Map<Integer, DraftCandidate> claimOwners) {}

    private EvidenceInput toModelEvidence(RuleEvidence evidence) {
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                evidence.excerpt(),
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
