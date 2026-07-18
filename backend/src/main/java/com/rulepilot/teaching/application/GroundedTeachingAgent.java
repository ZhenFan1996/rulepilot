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
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
    private static final int MAX_STEPS_PER_SECTION = 6;
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
        int toolCalls = 0;
        for (TeachingPlan.PlannedSection planned : plan.sections()) {
            if (toolCalls >= maxToolCalls) {
                log.warn("Teaching Agent tool budget exhausted before section {}", planned.type());
                sections.add(insufficient(planned));
                continue;
            }

            List<RuleEvidence> evidence;
            try {
                toolCalls++;
                evidence = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        "searchRuleEvidence",
                        estimateTokens(query(planned.type())),
                        "Version-scoped rule evidence retrieved",
                        () -> retrieve(plan.documentVersionId(), planned),
                        this::evidenceTokens);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException retrievalFailure) {
                log.warn(
                        "Teaching Agent retrieval failed for section {}: {}",
                        planned.type(),
                        retrievalFailure.getClass().getSimpleName());
                sections.add(insufficient(planned));
                continue;
            }
            if (evidence.isEmpty() || toolCalls >= maxToolCalls) {
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
                toolCalls++;
                sections.add(compose(plan, planned, evidence, assistantRunId));
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

    private List<RuleEvidence> retrieve(UUID documentVersionId, TeachingPlan.PlannedSection planned) {
        Set<String> sourceTypes = planned.required()
                ? Set.of(planned.type().name())
                : planned.dependencies().isEmpty()
                        ? Set.of(planned.type().name())
                        : planned.dependencies().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
        return List.copyOf(tools.searchRuleEvidence(new SearchRuleEvidence(
                        documentVersionId,
                        query(planned.type()),
                        MAX_EVIDENCE_PER_SECTION,
                        sourceTypes,
                        planned.type().name())));
    }

    private LessonSection compose(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<RuleEvidence> evidence,
            UUID assistantRunId) {
        TeachingLessonModel.SectionRequest modelRequest = new TeachingLessonModel.SectionRequest(
                planned.type(),
                plan.playerCount(),
                plan.beginnerCount(),
                plan.durationMinutes(),
                evidence.stream().map(this::toModelEvidence).toList());
        SectionDraft draft = invocations.invoke(
                assistantRunId,
                ActivityType.MODEL,
                "composeTeachingSection",
                estimateTokens(modelRequest.toString()),
                "Teaching section model output received",
                () -> model.compose(modelRequest),
                result -> estimateTokens(result.toString()));
        validateDraft(draft);

        var verification = evidenceVerifier.verify(new VerificationRequest(
                plan.documentVersionId(),
                evidence.stream().map(this::toVerifierEvidence).toList(),
                draft.steps().stream().map(step -> new EvidenceClaim(step.text(), step.citationIds())).toList()));
        if (!verification.verified()) {
            throw new IllegalArgumentException("teaching section evidence did not pass policy verification");
        }
        var review = critic.review(
                new ReviewRequest(
                        assistantRunId,
                        ContentType.LESSON,
                        IntStream.range(0, draft.steps().size())
                                .mapToObj(index -> new Claim(
                                        index + 1,
                                        draft.steps().get(index).text(),
                                        draft.steps().get(index).citationIds()))
                                .toList(),
                        evidence.stream()
                                .map(source -> new GeneratedContentCritic.Evidence(
                                        source.chunkId(), source.excerpt()))
                                .toList()),
                ReviewRisk.STANDARD);
        if (!review.accepted()) {
            throw new IllegalArgumentException("teaching section did not pass factual consistency review");
        }

        Map<UUID, RuleEvidence> allowedEvidence = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        RuleEvidence::chunkId, Function.identity(), (first, duplicate) -> first));
        List<LessonStep> steps = IntStream.range(0, draft.steps().size())
                .mapToObj(index -> validatedStep(index + 1, draft.steps().get(index), allowedEvidence))
                .toList();
        return new LessonSection(
                planned.position(),
                planned.type(),
                draft.title().strip(),
                planned.required(),
                EvidenceStatus.SUPPORTED,
                draft.visualKind(),
                draft.visualCaption().strip(),
                steps);
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

    private void validateDraft(SectionDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length() > 160
                || draft.visualKind() == null
                || draft.visualCaption() == null || draft.visualCaption().isBlank()
                || draft.visualCaption().length() > 240
                || draft.steps().isEmpty() || draft.steps().size() > MAX_STEPS_PER_SECTION) {
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

    private String query(TeachingSectionType type) {
        return switch (type) {
            case OBJECTIVE -> "objective victory goal winning condition 游戏目标 胜利条件";
            case COMPONENTS -> "components contents pieces cards board 组件 配件";
            case SETUP -> "setup preparation starting layout 开局 设置 布置";
            case ROUND_STRUCTURE -> "round turn order structure 轮次 回合 顺序";
            case PHASES -> "phase sequence steps 阶段 流程";
            case ACTIONS -> "actions player may can action 玩家 行动";
            case END_CONDITIONS -> "game end ending condition 游戏结束 条件";
            case SCORING -> "scoring points calculate score 计分 分数";
            case TIE_BREAKERS -> "tie tied winner tiebreak 同分 平局";
            case FIRST_ROUND_PRACTICE -> "first round example actions 首轮 演练";
            case COMMON_MISTAKES -> "important exception cannot remember 注意 例外";
            case RECAP -> "summary round scoring recap 总结 回顾";
        };
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
