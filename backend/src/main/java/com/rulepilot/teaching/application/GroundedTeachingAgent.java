package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
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
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.application.TeachingRetrievalPlanner.RetrievalIntent;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
    private static final int MAX_EVIDENCE_PER_SECTION = 6;
    private static final int EVIDENCE_PER_INTENT = 4;
    private static final int MAX_STEPS_PER_SECTION = 6;
    private static final Set<TeachingSectionType> HIGH_IMPACT_SECTIONS = Set.of(
            TeachingSectionType.SETUP,
            TeachingSectionType.END_CONDITIONS,
            TeachingSectionType.SCORING,
            TeachingSectionType.TIE_BREAKERS);
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
            @Value("${rulepilot.teaching.agent.max-tool-calls:24}") int maxToolCalls) {
        this.tools = tools;
        this.model = model;
        this.evidenceVerifier = evidenceVerifier;
        this.critic = critic;
        this.invocations = invocations;
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    public IllustratedLesson create(TeachingPlan plan, UUID assistantRunId) {
        List<LessonSection> sections = new ArrayList<>();
        Map<TeachingSectionType, TeachingPacingPolicy.SectionPacing> pacing = TeachingPacingPolicy.allocate(plan);
        int toolCalls = 0;
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            if (toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before section {}", planned.type());
                sections.add(insufficient(planned));
                continue;
            }

            Map<UUID, RuleEvidence> evidenceById = new LinkedHashMap<>();
            List<List<RuleEvidence>> evidenceByIntent = new ArrayList<>();
            boolean conflictingEvidence = false;
            List<RetrievalIntent> retrievalIntents = TeachingRetrievalPlanner.forSection(planned.type());
            for (int intentIndex = 0; intentIndex < retrievalIntents.size(); intentIndex++) {
                if (toolCalls >= maxToolCalls) {
                    break;
                }
                RetrievalIntent plannedIntent = retrievalIntents.get(intentIndex);
                RetrievalIntent intent = intentIndex == 0
                        ? plannedIntent
                        : TeachingRetrievalPlanner.refineWithAnchorHeadings(
                                plannedIntent, evidenceById.values().stream().map(RuleEvidence::heading).toList());
                toolCalls++;
                try {
                    List<RuleEvidence> retrieved = invocations.invoke(
                            assistantRunId,
                            ActivityType.TOOL,
                            "searchRuleEvidence",
                            estimateTokens(intent.query()),
                            "Version-scoped rule evidence retrieved",
                            () -> retrieve(plan.documentVersionId(), planned.type(), intent),
                            this::evidenceTokens);
                    evidenceByIntent.add(retrieved);
                    for (RuleEvidence source : retrieved) {
                        RuleEvidence existing = evidenceById.putIfAbsent(source.chunkId(), source);
                        if (existing != null && !existing.equals(source)) {
                            conflictingEvidence = true;
                            break;
                        }
                    }
                } catch (AgentExecutionStoppedException stopped) {
                    throw stopped;
                } catch (RuntimeException retrievalFailure) {
                    log.warn(
                            "Teaching Agent retrieval intent failed for section {}: {}",
                            planned.type(),
                            retrievalFailure.getClass().getSimpleName());
                }
                if (conflictingEvidence) {
                    log.warn("Teaching Agent retrieved conflicting snapshots for section {}", planned.type());
                    break;
                }
            }
            List<RuleEvidence> evidence = conflictingEvidence
                    ? List.of()
                    : balancedEvidence(evidenceByIntent);
            if (evidence.isEmpty()) {
                sections.add(insufficient(planned));
                continue;
            }
            if (!evidenceVerifier.verify(new VerificationRequest(
                            plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                    .verified()) {
                sections.add(insufficient(planned));
                continue;
            }

            try {
                sections.add(compose(
                        plan,
                        planned,
                        pacing.get(planned.type()),
                        continuityContext(sections),
                        evidence,
                        assistantRunId));
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException invalidOrFailedModelOutput) {
                log.warn(
                        "Teaching Agent model {} failed validation for section {}: {}",
                        model.providerId(),
                        planned.type(),
                        invalidOrFailedModelOutput.getClass().getSimpleName());
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
                Instant.now());
    }

    private List<RuleEvidence> retrieve(
            UUID documentVersionId, TeachingSectionType sectionType, RetrievalIntent intent) {
        return List.copyOf(tools.searchRuleEvidence(new SearchRuleEvidence(
                        documentVersionId,
                        intent.query(),
                        EVIDENCE_PER_INTENT,
                        intent.sourceTypes(),
                        sectionType.name(),
                        true)));
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
                planned.type(),
                plan.playerCount(),
                plan.beginnerCount(),
                plan.durationMinutes(),
                pacing.durationSeconds(),
                pacing.maxSteps(),
                priorSections,
                evidence.stream().map(this::toModelEvidence).toList());
        SectionDraft draft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                "composeTeachingSection",
                estimateTokens(modelRequest.toString()),
                "Teaching section model output received",
                () -> model.compose(modelRequest),
                result -> estimateTokens(result.toString()));
        validateDraft(draft, modelRequest.maxSteps());

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        List<UUID> visualCitationIds = validatedVisualCitationIds(draft, allowedEvidence);
        List<EvidenceClaim> generatedClaims = new ArrayList<>();
        generatedClaims.add(new EvidenceClaim(draft.visualCaption(), visualCitationIds));
        generatedClaims.addAll(draft.steps().stream()
                .map(step -> new EvidenceClaim(step.text(), step.citationIds()))
                .toList());
        var verification = evidenceVerifier.verify(new VerificationRequest(
                plan.documentVersionId(),
                evidence.stream().map(this::toVerifierEvidence).toList(),
                generatedClaims));
        if (!verification.verified()) {
            throw new IllegalArgumentException("teaching section evidence did not pass policy verification");
        }
        var guidance = TeachingSectionKnowledge.forSection(planned.type());
        var review = critic.review(
                new ReviewRequest(
                        assistantRunId,
                        ContentType.LESSON,
                        new TaskContext(guidance.objective(), guidance.coverageChecklist()),
                        Stream.concat(
                                        Stream.of(new Claim(1, draft.visualCaption(), visualCitationIds)),
                                        IntStream.range(0, draft.steps().size())
                                                .mapToObj(index -> new Claim(
                                                        index + 2,
                                                        draft.steps().get(index).text(),
                                                        draft.steps().get(index).citationIds())))
                                .toList(),
                        evidence.stream()
                                .map(source -> new GeneratedContentCritic.Evidence(
                                        source.chunkId(), source.excerpt()))
                                .toList()),
                HIGH_IMPACT_SECTIONS.contains(planned.type())
                        ? ReviewRisk.HIGH_IMPACT
                        : ReviewRisk.STANDARD);
        if (!review.accepted()) {
            throw new IllegalArgumentException("teaching section did not pass factual consistency review");
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
                planned.type(),
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
        return new LessonStep(position, draft.text().strip(), pages, List.copyOf(citationIds));
    }

    private void validateDraft(SectionDraft draft, int maxSteps) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length() > 160
                || draft.visualKind() == null
                || draft.visualCaption() == null || draft.visualCaption().isBlank()
                || draft.visualCaption().length() > 240
                || draft.visualCitationIds().isEmpty()
                || draft.steps().isEmpty() || draft.steps().size() > Math.min(MAX_STEPS_PER_SECTION, maxSteps)) {
            throw new IllegalArgumentException("teaching section output is invalid");
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

    private List<PriorSectionContext> continuityContext(List<LessonSection> sections) {
        List<LessonSection> supported = sections.stream()
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .toList();
        int fromIndex = Math.max(0, supported.size() - 2);
        return supported.subList(fromIndex, supported.size()).stream()
                .map(section -> new PriorSectionContext(
                        section.type(), section.title(), section.steps().getLast().text()))
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
                planned.type(),
                label(planned.type()),
                planned.required(),
                EvidenceStatus.INSUFFICIENT_EVIDENCE,
                VisualKind.REFERENCE_CARD,
                "本节等待可验证的规则证据",
                List.of(new LessonStep(
                        1,
                        "规则资料中尚未找到这一节所需的可靠证据。",
                        List.of(),
                        List.of())));
    }

    private String label(TeachingSectionType type) {
        return type.name().replace('_', ' ');
    }

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
