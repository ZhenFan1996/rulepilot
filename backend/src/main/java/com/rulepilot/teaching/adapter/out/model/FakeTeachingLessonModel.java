package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeTeachingLessonModel implements TeachingLessonModel {

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public SectionDraft compose(SectionRequest request) {
        List<StepDraft> steps = request.evidence().stream()
                .limit(6)
                .map(source -> new StepDraft(source.excerpt(), List.of(source.chunkId())))
                .toList();
        return new SectionDraft(
                label(request.sectionType()),
                visual(request.sectionType()),
                "按引用证据逐步完成本节操作",
                steps);
    }

    private String label(com.rulepilot.teaching.domain.TeachingSectionType type) {
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

    private VisualKind visual(com.rulepilot.teaching.domain.TeachingSectionType type) {
        return switch (type) {
            case SETUP, COMPONENTS -> VisualKind.TABLE_LAYOUT;
            case ROUND_STRUCTURE, PHASES, ACTIONS, FIRST_ROUND_PRACTICE, RECAP -> VisualKind.FLOW_DIAGRAM;
            case SCORING, TIE_BREAKERS -> VisualKind.SCOREBOARD;
            default -> VisualKind.REFERENCE_CARD;
        };
    }
}
