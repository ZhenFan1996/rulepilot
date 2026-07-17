package com.rulepilot.ingestion.domain;

import java.util.List;

public enum LessonRuleSectionType {
    OBJECTIVE("目标与胜利条件", List.of("objective", "goal", "how to win", "winning the game", "游戏目标", "获胜条件", "胜利条件")),
    COMPONENTS("组件与用途", List.of("components", "contents", "game pieces", "组件", "配件", "游戏物件")),
    SETUP("Setup", List.of("setup", "set up", "preparation", "准备游戏", "游戏设置", "开局准备", "设置")),
    ROUND_STRUCTURE("轮次与回合", List.of("round structure", "turn structure", "game round", "game turn", "轮次", "回合结构", "回合流程")),
    PHASES("阶段", List.of("phases", "phase", "阶段")),
    ACTIONS("可执行行动", List.of("actions", "action", "player may", "you may", "行动", "动作", "玩家可以")),
    END_CONDITIONS("结束条件", List.of("game end", "end of the game", "ending the game", "结束游戏", "游戏结束", "结束条件")),
    SCORING("计分", List.of("scoring", "score", "victory points", "final points", "计分", "得分", "胜利点")),
    TIE_BREAKERS("同分规则", List.of("tie breaker", "tie-breaker", "ties are", "in case of a tie", "平局", "同分", "打破平手"));

    private final String label;
    private final List<String> keywords;

    LessonRuleSectionType(String label, List<String> keywords) {
        this.label = label;
        this.keywords = keywords;
    }

    public String label() {
        return label;
    }

    public List<String> keywords() {
        return keywords;
    }
}
