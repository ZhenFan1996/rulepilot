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
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GroundedTeachingAgent {

    private static final Logger log = LoggerFactory.getLogger(GroundedTeachingAgent.class);
    static final String GENERATOR_VERSION = "adaptive-teaching-v5";
    private static final Set<String> REUSABLE_GENERATOR_VERSIONS =
            Set.of("adaptive-teaching-v3", "adaptive-teaching-v4", GENERATOR_VERSION);
    private static final int MAX_EVIDENCE_PER_SECTION = 12;
    private static final int EVIDENCE_PER_INTENT = 4;
    private static final int MAX_STEPS_PER_SECTION = 6;
    private static final int MAX_REVISION_ATTEMPTS = 4;
    private static final Pattern UNRESOLVED_PDF_MARKER = Pattern.compile("\\[[A-Za-z][A-Za-z _-]{0,30}]");
    private static final Pattern UNRESOLVED_EMOJI_ICON = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
    private static final Pattern TEXT_ONLY_PRESENTATION_MARKER = Pattern.compile(
            "(?i)(attached|attachment|image|rulebook|page\\s*\\d|图片|附件|规则书|第\\s*\\d+\\s*页|页面)");
    private static final Pattern RETRIEVAL_QUERY_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}'’-]+");
    private static final Set<String> ENGLISH_RETRIEVAL_FILLER = Set.of(
            "a", "an", "and", "are", "do", "does", "for", "how", "is", "of", "the", "to", "what", "when",
            "with", "you", "your");
    private static final Set<String> HIGH_IMPACT_TAGS = Set.of("setup", "end", "scoring", "tie_breaker");
    private final AssistantReadTools tools;
    private final TeachingLessonModel model;
    private final EvidenceVerifier evidenceVerifier;
    private final GeneratedContentCritic critic;
    private final AuditedAgentInvocations invocations;
    private final int maxToolCalls;

    public GroundedTeachingAgent(
            AssistantReadTools tools,
            TeachingLessonModel model,
            EvidenceVerifier evidenceVerifier,
            GeneratedContentCritic critic,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.teaching.agent.max-tool-calls:72}") int maxToolCalls) {
        this.tools = tools;
        this.model = model;
        this.evidenceVerifier = evidenceVerifier;
        this.critic = critic;
        this.invocations = invocations;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public IllustratedLesson create(TeachingPlan plan, UUID assistantRunId) {
        return create(plan, assistantRunId, null);
    }

    public IllustratedLesson create(
            TeachingPlan plan, UUID assistantRunId, IllustratedLesson previousLesson) {
        List<LessonSection> sections = new ArrayList<>();
        Map<String, LessonSection> reusableSections = reusableSections(plan, previousLesson);
        Map<Integer, TeachingPacingPolicy.SectionPacing> pacing = TeachingPacingPolicy.allocate(plan);
        int toolCalls = 0;
        int queriesPerTopic = Math.max(1, Math.min(6, maxToolCalls / plan.sections().size()));
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            LessonSection reusable = reusableSections.get(planned.topicKey());
            if (reusable != null) {
                log.info("Teaching topic {} reuses a previously verified section", planned.topicKey());
                sections.add(reusable);
                continue;
            }
            if (toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before topic {}", planned.topicKey());
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "TOOL_BUDGET_EXHAUSTED");
                sections.add(insufficient(planned));
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
                continue;
            }
            if (!evidenceVerifier.verify(new VerificationRequest(
                            plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                    .verified()) {
                recordPublication(assistantRunId, planned, ActivityOutcome.REJECTED, "RETRIEVED_EVIDENCE_INVALID");
                sections.add(insufficient(planned));
                continue;
            }

            try {
                LessonSection composed = compose(
                        plan,
                        planned,
                        pacing.get(planned.position()),
                        continuityContext(sections),
                        evidence,
                        assistantRunId);
                sections.add(composed);
                recordPublication(assistantRunId, planned, ActivityOutcome.SUCCEEDED, "DRAFT_ACCEPTED");
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
                        "DRAFT_WITHHELD_AFTER_BOUNDED_REVISIONS");
                sections.add(insufficient(planned));
            }
        }

        boolean complete = sections.stream()
                .filter(LessonSection::required)
                .allMatch(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED);
        return new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                complete ? LessonStatus.COMPLETE : LessonStatus.INCOMPLETE,
                sections,
                GENERATOR_VERSION,
                Instant.now());
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
        return previousLesson.sections().stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .filter(section -> currentTopics.contains(section.topicKey()))
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
        Stream<String> queries = topic.retrievalQueries().stream();
        if (topic.retrievalQueries().size() == 4) {
            queries = Stream.concat(objectiveQueries(topic.objective()).stream(), queries);
        }
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

    private LessonSection compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            TeachingPacingPolicy.SectionPacing pacing,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            UUID assistantRunId) {
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
                selectedPageImages(planned, evidence));
        SectionDraft draft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                operationName("composeTeachingSection", planned.position()),
                estimateTokens(modelRequest.toString()),
                "Teaching section model output received",
                () -> model.compose(modelRequest),
                result -> estimateTokens(result.toString()));
        draft = normalizePresentationMetadata(draft, modelRequest.pageImages().isEmpty());
        String previousRejection = null;
        for (int revision = 0; ; revision++) {
            try {
                LessonSection accepted = acceptDraft(plan, planned, evidence, assistantRunId, modelRequest, draft);
                recordValidation(
                        assistantRunId,
                        planned,
                        revision,
                        ActivityOutcome.SUCCEEDED,
                        "DRAFT_ACCEPTED");
                return accepted;
            } catch (IllegalArgumentException rejectedDraft) {
                String rejection = rejectionFingerprint(rejectedDraft);
                recordValidation(
                        assistantRunId,
                        planned,
                        revision,
                        ActivityOutcome.REJECTED,
                        rejectionCategory(rejectedDraft));
                if (revision == MAX_REVISION_ATTEMPTS || rejection.equals(previousRejection)) {
                    throw rejectedDraft;
                }
                previousRejection = rejection;
                List<String> feedback = List.of(rejectedDraft.getMessage() == null
                        ? "The previous draft failed lesson validation."
                        : rejectedDraft.getMessage());
                log.info(
                        "Teaching topic {} revision {}/{}: {}",
                        planned.topicKey(),
                        revision + 1,
                        MAX_REVISION_ATTEMPTS,
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
                draft = normalizePresentationMetadata(draft, modelRequest.pageImages().isEmpty());
            }
        }
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
            TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence) {
        if (!planned.visualEvidenceRecommended() || !model.supportsVisualEvidence()) return List.of();
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
                        scores.merge(image.pageNumber(), sourceScore, Integer::sum);
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

    private LessonSection acceptDraft(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            UUID assistantRunId,
            TeachingLessonModel.SectionRequest modelRequest,
            SectionDraft draft) {
        validateDraft(draft, modelRequest);

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        validateVisualBlockEvidence(draft, modelRequest, allowedEvidence);
        List<UUID> visualCitationIds = validatedVisualCitationIds(draft, allowedEvidence);
        List<EvidenceClaim> generatedClaims = new ArrayList<>();
        generatedClaims.add(new EvidenceClaim(draft.visualCaption(), visualCitationIds));
        generatedClaims.addAll(draft.steps().stream()
                .map(step -> new EvidenceClaim(step.heading() + "：" + step.text(), step.citationIds()))
                .toList());
        var verification = evidenceVerifier.verify(new VerificationRequest(
                plan.documentVersionId(),
                evidence.stream().map(this::toVerifierEvidence).toList(),
                generatedClaims));
        if (!verification.verified()) {
            throw new IllegalArgumentException(
                    "Evidence validation failed: " + String.join(", ", verification.issueCodes()));
        }
        Set<UUID> citedEvidenceIds = generatedClaims.stream()
                .flatMap(claim -> claim.citationIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        var review = critic.review(
                new ReviewRequest(
                        assistantRunId,
                        ContentType.LESSON,
                        new TaskContext(planned.objective(), String.join(", ", planned.coverageTags())),
                        Stream.concat(
                                        Stream.of(new Claim(1, draft.visualCaption(), visualCitationIds)),
                                        IntStream.range(0, draft.steps().size())
                                                .mapToObj(index -> new Claim(
                                                        index + 2,
                                                        draft.steps().get(index).heading() + "："
                                                                + draft.steps().get(index).text(),
                                                        draft.steps().get(index).citationIds())))
                                .toList(),
                        evidence.stream()
                                .filter(source -> citedEvidenceIds.contains(source.chunkId()))
                                .map(source -> new GeneratedContentCritic.Evidence(
                                        source.chunkId(), source.excerpt()))
                                .toList()),
                planned.coverageTags().stream().anyMatch(HIGH_IMPACT_TAGS::contains)
                        ? ReviewRisk.HIGH_IMPACT
                        : ReviewRisk.LOW_CONFIDENCE);
        if (!review.accepted()) {
            String fingerprint = review.issues().stream()
                    .map(issue -> issue.type() + ":" + issue.claimPosition() + ":" + issue.evidenceIds().stream()
                            .map(UUID::toString)
                            .sorted()
                            .collect(Collectors.joining(",")))
                    .sorted()
                    .collect(Collectors.joining(";"));
            throw new RejectedTeachingDraftException(
                    fingerprint,
                    criticDiagnostic(review.issues()),
                    "Factual review rejected the draft: " + review.issues().stream()
                    .map(issue -> issue.type() + " evidence=" + issue.evidenceIds() + " - " + issue.summary())
                    .collect(Collectors.joining("; ")));
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
                EvidenceStatus.SUPPORTED,
                draft.visualKind(),
                draft.visualCaption().strip(),
                visualSourcePages,
                visualCitationIds,
                steps);
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
            boolean citesFocusPage = step.citationIds().stream()
                    .map(allowedEvidence::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(source -> focus.pageNumber() >= source.pageFrom()
                            && focus.pageNumber() <= source.pageTo());
            if (!citesFocusPage) {
                throw new IllegalArgumentException(
                        "VISUAL focus page must be covered by the block's cited evidence.");
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
        return new VisualFocus(
                focus.pageNumber(), focus.label(), focus.x(), focus.y(), focus.width(), focus.height());
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
    }

    private String rejectionFingerprint(IllegalArgumentException rejection) {
        if (rejection instanceof RejectedTeachingDraftException criticRejection) {
            return "critic:" + criticRejection.fingerprint();
        }
        return "validation:" + (rejection.getMessage() == null ? rejection.getClass().getName() : rejection.getMessage());
    }

    private String rejectionCategory(IllegalArgumentException rejection) {
        if (rejection instanceof RejectedTeachingDraftException criticRejection) {
            return criticRejection.diagnostic();
        }
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

    private static final class RejectedTeachingDraftException extends IllegalArgumentException {
        private final String fingerprint;
        private final String diagnostic;

        private RejectedTeachingDraftException(String fingerprint, String diagnostic, String message) {
            super(message);
            this.fingerprint = fingerprint;
            this.diagnostic = diagnostic;
        }

        private String fingerprint() {
            return fingerprint;
        }

        private String diagnostic() {
            return diagnostic;
        }
    }

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
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
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
