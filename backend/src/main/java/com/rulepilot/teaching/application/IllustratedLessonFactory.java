package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.RuleStructureCatalog.SectionView;
import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class IllustratedLessonFactory {

    private static final int MAX_STEPS_PER_SECTION = 6;
    private static final int MAX_STEP_LENGTH = 600;

    public IllustratedLesson create(TeachingPlan plan, StructureView structure) {
        Map<String, SectionView> evidence = structure.sections().stream()
                .collect(Collectors.toMap(SectionView::type, Function.identity()));
        List<LessonSection> sections = plan.sections().stream()
                .map(planned -> compose(planned, evidence))
                .toList();
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

    private LessonSection compose(
            TeachingPlan.PlannedSection planned, Map<String, SectionView> evidence) {
        TeachingSectionType sourceType = planned.required()
                ? planned.type()
                : planned.dependencies().stream().findFirst().orElse(planned.type());
        SectionView source = evidence.get(sourceType.name());
        boolean supported = source != null && source.present() && !source.content().isBlank();
        List<LessonStep> steps = supported
                ? steps(source.content(), source.pageNumbers())
                : List.of(new LessonStep(1, "规则资料中尚未找到这一节所需的可靠证据。", List.of()));
        return new LessonSection(
                planned.position(),
                planned.type(),
                label(planned.type()),
                planned.required(),
                supported ? EvidenceStatus.SUPPORTED : EvidenceStatus.INSUFFICIENT_EVIDENCE,
                visual(planned.type()),
                visualCaption(planned.type()),
                steps);
    }

    private List<LessonStep> steps(String content, List<Integer> sourcePages) {
        List<String> candidates = Arrays.stream(content.split("(?:\\R\\s*){2,}|(?<=[。！？.!?])\\s+"))
                .map(String::strip)
                .filter(part -> !part.isBlank())
                .limit(MAX_STEPS_PER_SECTION)
                .toList();
        List<String> parts = candidates.isEmpty() ? List.of(content.strip()) : candidates;
        return java.util.stream.IntStream.range(0, parts.size())
                .mapToObj(index -> new LessonStep(index + 1, bounded(parts.get(index)), sourcePages))
                .toList();
    }

    private String bounded(String text) {
        return text.length() <= MAX_STEP_LENGTH ? text : text.substring(0, MAX_STEP_LENGTH) + "…";
    }

    private String label(TeachingSectionType type) {
        return switch (type) {
            case OBJECTIVE -> "目标与胜利条件";
            case COMPONENTS -> "组件与用途";
            case SETUP -> "Setup：完成开局布置";
            case ROUND_STRUCTURE -> "轮次与回合结构";
            case PHASES -> "阶段流程";
            case ACTIONS -> "玩家行动";
            case END_CONDITIONS -> "游戏结束";
            case SCORING -> "最终计分";
            case TIE_BREAKERS -> "同分处理";
            case FIRST_ROUND_PRACTICE -> "首轮演练";
            case COMMON_MISTAKES -> "常见错误提醒";
            case RECAP -> "开局前流程回顾";
        };
    }

    private VisualKind visual(TeachingSectionType type) {
        return switch (type) {
            case SETUP, COMPONENTS -> VisualKind.TABLE_LAYOUT;
            case ROUND_STRUCTURE, PHASES, ACTIONS, FIRST_ROUND_PRACTICE, RECAP -> VisualKind.FLOW_DIAGRAM;
            case SCORING, TIE_BREAKERS -> VisualKind.SCOREBOARD;
            default -> VisualKind.REFERENCE_CARD;
        };
    }

    private String visualCaption(TeachingSectionType type) {
        return switch (visual(type)) {
            case TABLE_LAYOUT -> "按步骤核对桌面区域与物件";
            case FLOW_DIAGRAM -> "按顺序推进这一节的规则步骤";
            case SCOREBOARD -> "集中核对计分项目与结算顺序";
            case REFERENCE_CARD -> "本节关键规则证据卡";
        };
    }
}
