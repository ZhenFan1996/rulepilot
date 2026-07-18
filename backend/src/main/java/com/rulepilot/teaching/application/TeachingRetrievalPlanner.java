package com.rulepilot.teaching.application;

import static com.rulepilot.teaching.domain.TeachingSectionType.ACTIONS;
import static com.rulepilot.teaching.domain.TeachingSectionType.COMPONENTS;
import static com.rulepilot.teaching.domain.TeachingSectionType.END_CONDITIONS;
import static com.rulepilot.teaching.domain.TeachingSectionType.OBJECTIVE;
import static com.rulepilot.teaching.domain.TeachingSectionType.PHASES;
import static com.rulepilot.teaching.domain.TeachingSectionType.ROUND_STRUCTURE;
import static com.rulepilot.teaching.domain.TeachingSectionType.SCORING;
import static com.rulepilot.teaching.domain.TeachingSectionType.SETUP;
import static com.rulepilot.teaching.domain.TeachingSectionType.TIE_BREAKERS;

import com.rulepilot.teaching.domain.TeachingSectionType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class TeachingRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_ANCHOR_HEADINGS = 2;
    private static final int MAX_HEADING_LENGTH = 80;

    private TeachingRetrievalPlanner() {}

    public static List<RetrievalIntent> forSection(TeachingSectionType type) {
        return switch (type) {
            case OBJECTIVE -> intents(
                    intent("objective victory goal winner 游戏目标 胜利条件", OBJECTIVE),
                    intent("winning condition final objective achieve victory 获胜 达成目标", OBJECTIVE));
            case COMPONENTS -> intents(
                    intent("components contents pieces cards board quantities 组件 配件 数量", COMPONENTS),
                    intent("player pieces shared supply starting components 玩家配件 公共供应", COMPONENTS));
            case SETUP -> intents(
                    intent("setup preparation table layout player area 开局 设置 桌面布置", SETUP),
                    intent("starting player initial cards resources per player 起始玩家 初始手牌 资源", SETUP));
            case ROUND_STRUCTURE -> intents(
                    intent("round turn order active player round end 轮次 回合 顺序", ROUND_STRUCTURE),
                    intent("round begins phase sequence next player repeat 轮次开始 阶段 下一位玩家", ROUND_STRUCTURE));
            case PHASES -> intents(
                    intent("phase order sequence mandatory steps 阶段 顺序 必须步骤", PHASES),
                    intent("phase transition active player timing 阶段转换 当前玩家 时机", PHASES));
            case ACTIONS -> intents(
                    intent("available actions choose action player turn 可选行动 玩家回合", ACTIONS),
                    intent("action prerequisite cost limit result cannot 行动 前置条件 费用 限制 结果", ACTIONS));
            case END_CONDITIONS -> intents(
                    intent("game end trigger final round stop play 游戏结束 触发 最后一轮", END_CONDITIONS),
                    intent("end condition finish current turn proceed scoring 结束条件 完成本回合 进入计分", END_CONDITIONS));
            case SCORING -> intents(
                    intent("scoring points calculate total bonuses penalties 计分 分数 奖励 惩罚", SCORING),
                    intent("final score winner compare categories end game 最终得分 胜者 比较", SCORING));
            case TIE_BREAKERS -> intents(
                    intent("tie tied score tiebreak winner 同分 平局 决胜", TIE_BREAKERS),
                    intent("equal final score compare remaining resources shared victory 同分比较 剩余资源", TIE_BREAKERS));
            case FIRST_ROUND_PRACTICE -> intents(
                    intent("first round first turn legal action example 首轮 第一回合 行动示例", ACTIONS, PHASES),
                    intent("starting state turn sequence next player 起始状态 回合顺序 下一位玩家", SETUP, ROUND_STRUCTURE, ACTIONS));
            case COMMON_MISTAKES -> intents(
                    intent("important exception restriction cannot only timing 注意 例外 限制 不可", ACTIONS, PHASES),
                    intent("setup scoring exception reminder overlooked 设置 计分 例外 易错", SETUP, SCORING, TIE_BREAKERS));
            case RECAP -> intents(
                    intent("objective round turn actions summary 目标 轮次 回合 行动 总结", OBJECTIVE, ROUND_STRUCTURE, ACTIONS),
                    intent("game end scoring tie summary 结束 计分 同分 回顾", END_CONDITIONS, SCORING, TIE_BREAKERS));
        };
    }

    static RetrievalIntent refineWithAnchorHeadings(RetrievalIntent intent, List<String> headings) {
        if (intent == null || headings == null) {
            throw new IllegalArgumentException("teaching retrieval refinement input is required");
        }
        StringBuilder query = new StringBuilder(intent.query());
        String normalizedQuery = intent.query().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        for (String heading : headings) {
            String anchor = normalizedHeading(heading);
            if (!anchor.isEmpty() && !normalizedQuery.contains(anchor.toLowerCase(Locale.ROOT))) {
                anchors.add(anchor);
            }
            if (anchors.size() == MAX_ANCHOR_HEADINGS) {
                break;
            }
        }
        for (String anchor : anchors) {
            if (query.length() + anchor.length() + 1 > MAX_QUERY_LENGTH) {
                break;
            }
            query.append(' ').append(anchor);
        }
        return new RetrievalIntent(query.toString(), intent.sourceTypes());
    }

    private static String normalizedHeading(String heading) {
        if (heading == null || heading.isBlank()) {
            return "";
        }
        String normalized = heading.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_HEADING_LENGTH
                ? normalized
                : normalized.substring(0, MAX_HEADING_LENGTH).strip();
    }

    private static List<RetrievalIntent> intents(RetrievalIntent primary, RetrievalIntent supplementary) {
        return List.of(primary, supplementary);
    }

    private static RetrievalIntent intent(String query, TeachingSectionType... sourceTypes) {
        return new RetrievalIntent(
                query,
                Arrays.stream(sourceTypes).map(Enum::name).collect(Collectors.toUnmodifiableSet()));
    }

    public record RetrievalIntent(String query, Set<String> sourceTypes) {
        public RetrievalIntent {
            if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                    || sourceTypes == null || sourceTypes.isEmpty()) {
                throw new IllegalArgumentException("teaching retrieval intent is invalid");
            }
            query = query.strip();
            sourceTypes = Set.copyOf(sourceTypes);
        }
    }
}
