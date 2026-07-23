package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_SECTION_FILTERS = 4;
    private static final int MAX_INTENTS = 5;
    private static final java.util.regex.Pattern QUESTION_PART_SEPARATOR =
            java.util.regex.Pattern.compile("[?？!！;；]+");
    private static final Set<String> KNOWN_SECTIONS = Set.of(
            "OBJECTIVE",
            "COMPONENTS",
            "SETUP",
            "ROUND_STRUCTURE",
            "PHASES",
            "ACTIONS",
            "END_CONDITIONS",
            "SCORING",
            "TIE_BREAKERS");

    private AnswerRetrievalPlanner() {}

    public static List<RetrievalIntent> plan(UnderstoodQuestion question, QuestionContext context) {
        return plan(question, context, List.of());
    }

    public static List<RetrievalIntent> plan(
            UnderstoodQuestion question, QuestionContext context, List<String> rewrittenQueries) {
        if (question == null || context == null) {
            throw new IllegalArgumentException("answer retrieval planning input is required");
        }
        String currentSection = knownSection(context.currentLessonSection());
        List<RetrievalIntent> intents = new ArrayList<>();
        String endgameResolutionQuery = endgameResolutionQuery(question.normalizedQuestion());
        if (endgameResolutionQuery != null) {
            intents.add(new RetrievalIntent(endgameResolutionQuery, Set.of(), null));
        }
        String exhaustedSourceQuery = exhaustedSourceQuery(question.normalizedQuestion());
        if (exhaustedSourceQuery != null) {
            intents.add(new RetrievalIntent(exhaustedSourceQuery, Set.of(), null));
        }
        String endTurnProcedureQuery = endTurnProcedureQuery(question.normalizedQuestion());
        if (endTurnProcedureQuery != null) {
            intents.add(new RetrievalIntent(endTurnProcedureQuery, Set.of(), null));
        }
        String stateTransitionQuery = stateTransitionQuery(question.normalizedQuestion());
        if (stateTransitionQuery != null) {
            intents.add(new RetrievalIntent(stateTransitionQuery, Set.of(), null));
        }
        String roundResetQuery = roundResetQuery(question.normalizedQuestion());
        if (roundResetQuery != null) {
            intents.add(new RetrievalIntent(roundResetQuery, Set.of(), null));
        }
        String deferredTurnQuery = deferredTurnQuery(question.normalizedQuestion());
        if (deferredTurnQuery != null) {
            intents.add(new RetrievalIntent(deferredTurnQuery, Set.of(), null));
        }
        String contextualQuestion = contextualQuestion(question.normalizedQuestion(), context.previousQuestion());
        List<String> parts = questionParts(contextualQuestion);
        Set<String> learningScope = context.learningIntent() != null && currentSection != null
                ? Set.of(currentSection)
                : Set.of();
        int availableBeforeSupplementary = Math.max(1, MAX_INTENTS - intents.size() - 1);
        if (parts.size() == 1) {
            int rewriteBudget = Math.max(0, availableBeforeSupplementary - 1);
            addRewrittenQueries(intents, rewrittenQueries, rewriteBudget);
            intents.add(new RetrievalIntent(
                    expandSearchTerms(question.normalizedQuestion()), learningScope, currentSection, true));
        } else {
            parts.stream().limit(availableBeforeSupplementary).forEach(part -> intents.add(new RetrievalIntent(
                    expandSearchTerms(part), learningScope, currentSection, true)));
            int rewriteBudget = Math.max(0, MAX_INTENTS - intents.size() - 1);
            addRewrittenQueries(intents, rewrittenQueries, rewriteBudget);
        }
        intents.add(new RetrievalIntent(
                supplementaryQuery(question, context),
                inferredSections(question, currentSection),
                currentSection));
        return intents.stream().limit(MAX_INTENTS).toList();
    }

    private static String contextualQuestion(String question, String previousQuestion) {
        if (previousQuestion == null) {
            return question;
        }
        return bounded("previous question: " + previousQuestion + " follow-up: " + question);
    }

    private static List<String> questionParts(String question) {
        List<String> parts = QUESTION_PART_SEPARATOR.splitAsStream(question)
                .map(String::strip)
                .filter(part -> part.length() >= 2)
                .distinct()
                .limit(MAX_INTENTS - 1L)
                .toList();
        return parts.isEmpty() ? List.of(question) : parts;
    }

    private static void addRewrittenQueries(
            List<RetrievalIntent> intents, List<String> rewrittenQueries, int rewriteBudget) {
        if (rewrittenQueries == null || rewriteBudget <= 0) return;
        rewrittenQueries.stream()
                .map(AnswerRetrievalPlanner::bounded)
                .filter(query -> !query.isBlank())
                .distinct()
                .limit(rewriteBudget)
                .forEach(query -> intents.add(new RetrievalIntent(query, Set.of(), null)));
    }

    private static String expandSearchTerms(String questionPart) {
        return bounded(questionPart);
    }

    private static String supplementaryQuery(UnderstoodQuestion question, QuestionContext context) {
        StringBuilder query = new StringBuilder(contextualQuestion(
                question.normalizedQuestion(), context.previousQuestion()));
        if (!question.terms().isEmpty()) {
            append(query, String.join(" ", question.terms()));
        }
        append(query, facets(question.type()));
        append(query, endgameResolutionTerms(question.normalizedQuestion()));
        if (context.currentLessonSection() != null) {
            append(query, context.currentLessonSection().replace('_', ' '));
        }
        if (context.gamePhase() != null) {
            append(query, context.gamePhase().replace('_', ' '));
        }
        if (context.playerCount() != null) {
            append(query, context.playerCount() + " players " + context.playerCount() + "人");
        }
        append(query, learningFacets(context.learningIntent()));
        return bounded(query.toString());
    }

    private static String learningFacets(LearningIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case SIMPLIFY -> "core rule sequence must remember 核心规则 顺序 必须记住";
            case EXAMPLE -> "worked example legal sequence cost result 具体示例 合法步骤 费用 结果";
            case WHY -> "rule prerequisite consequence order 前置条件 规则后果 执行顺序";
            case EXCEPTIONS -> "restriction timing limit exception cannot 限制 时机 次数 例外 禁止";
        };
    }

    private static String facets(QuestionType type) {
        return switch (type) {
            case LESSON_STEP_FOLLOW_UP -> "step prerequisite consequence exception 步骤 前置条件 结果 例外";
            case RULE_QUERY -> "rule definition timing restriction exception 规则 定义 时机 限制 例外";
            case SITUATION_QUERY -> "legal action prerequisite timing cost exception 合法行动 前置条件 时机 费用 例外";
        };
    }

    private static String endgameResolutionTerms(String question) {
        if (!isEndgameResolutionQuestion(question)) return null;
        return "end game end of round cleanup end-game check fame scoring winner tie gold "
                + "pledged cargo final resolution 游戏结束 轮末 清理 结束检查 名声 声望 计分 胜者 平局 金币";
    }

    private static String endgameResolutionQuery(String question) {
        String terms = endgameResolutionTerms(question);
        return terms == null ? null : bounded(question + " " + terms);
    }

    private static boolean isEndgameResolutionQuestion(String question) {
        boolean mentionsEndTrigger = containsAny(
                question,
                "game end", "game ends", "end of round", "游戏结束", "轮末", "达到30", "30名声", "30声望", "30 fame");
        boolean mentionsResolution = containsAny(
                question,
                "end", "score", "tie", "winner", "cargo", "结束", "计分", "平局", "获胜", "货物", "名声", "声望");
        return mentionsEndTrigger && mentionsResolution;
    }

    private static Set<String> inferredSections(UnderstoodQuestion question, String currentSection) {
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        if (currentSection != null) {
            sections.add(currentSection);
        }
        String text = question.normalizedQuestion();
        addWhenContains(sections, text, "SETUP", "setup", "starting", "开局", "设置", "布置");
        if (containsAny(text, "tie", "tied", "平局", "同分")) {
            sections.add("TIE_BREAKERS");
            sections.add("END_CONDITIONS");
            sections.add("SCORING");
        }
        addWhenContains(
                sections, text, "SCORING", "score", "point", "scoring", "计分", "得分", "分数", "名声", "声望");
        addWhenContains(
                sections, text, "END_CONDITIONS", "game end", "ending", "end of round", "结束条件", "游戏结束", "轮末", "结束");
        addWhenContains(sections, text, "ACTIONS", "action", "play card", "行动", "打出", "卡牌");
        addWhenContains(sections, text, "PHASES", "phase", "trick", "阶段", "墩");
        addWhenContains(
                sections,
                text,
                "ROUND_STRUCTURE",
                "round",
                "turn order",
                "next trick",
                "lead",
                "轮次",
                "一轮",
                "回合结束",
                "轮次结束",
                "轮结束",
                "下一次轮到",
                "剩下的骰子",
                "剩余骰子",
                "回合顺序",
                "下一墩",
                "领出");
        addWhenContains(sections, text, "COMPONENTS", "component", "piece", "组件", "配件", "棋子");
        addWhenContains(sections, text, "OBJECTIVE", "objective", "win", "目标", "获胜", "胜利");
        return sections.stream().limit(MAX_SECTION_FILTERS).collect(Collectors.toUnmodifiableSet());
    }

    private static void addWhenContains(
            Set<String> sections, String text, String section, String... indicators) {
        if (containsAny(text, indicators)) {
            sections.add(section);
        }
    }

    private static boolean containsAny(String text, String... indicators) {
        return Arrays.stream(indicators).anyMatch(text::contains);
    }

    private static String knownSection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (KNOWN_SECTIONS.contains(normalized)) {
            return normalized;
        }
        if (containsAny(normalized, "SETUP", "设置", "布置")) return "SETUP";
        if (containsAny(normalized, "SCOR", "计分", "得分")) return "SCORING";
        if (containsAny(normalized, "TIE", "同分", "平局")) return "TIE_BREAKERS";
        if (containsAny(normalized, "TURN", "ROUND", "PASS", "FIRST_ROUND", "回合", "轮次")) {
            return "ROUND_STRUCTURE";
        }
        if (containsAny(normalized, "END", "结束")) return "END_CONDITIONS";
        if (containsAny(
                normalized, "ACTION", "CARD", "CORE_LOOP", "行动")) {
            return "ACTIONS";
        }
        if (containsAny(normalized, "COMPONENT", "组件", "配件")) return "COMPONENTS";
        if (containsAny(normalized, "GOAL", "OBJECTIVE", "WINNER", "目标", "胜利")) return "OBJECTIVE";
        return null;
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank() && target.indexOf(value) < 0) {
            target.append(' ').append(value.strip());
        }
    }

    private static String stateTransitionQuery(String question) {
        boolean actorExits = containsAny(
                question, "out of cards", "empty hand", "no cards", "runs out of cards", "出完", "无牌", "没有手牌", "手牌为零");
        boolean asksNextActor = containsAny(
                question, "next trick", "who leads", "who starts", "下一墩", "谁领出", "谁开始", "由谁");
        if (!actorExits || !asksNextActor) return null;
        return bounded(question + " state transition successor actor replacement active player skipped "
                + "after hand empty next trick lead exception 状态变化 后继行动者 替代玩家 无牌 跳过 下一墩 领出 例外");
    }

    private static String exhaustedSourceQuery(String question) {
        boolean asksAboutSourceArea = containsAny(
                question,
                "draw zone",
                "draw dice",
                "draw amount",
                "deck",
                "pile",
                "supply",
                "pool",
                "draw",
                "take",
                "refill",
                "抽",
                "摸",
                "取",
                "补",
                "拿",
                "牌堆",
                "供应",
                "区域");
        boolean asksAboutShortage = containsAny(
                question,
                "not enough",
                "insufficient",
                "empty",
                "runs out",
                "不足",
                "不够",
                "用完",
                "没有骰子");
        if (!asksAboutSourceArea || !asksAboutShortage) return null;
        return bounded(question + " source area supply pile deck depleted empty insufficient discard return recycle "
                + "refill reshuffle continue procedure 资源区 牌堆 供应区 耗尽 为空 弃置 回收 移回 补充 洗混 继续");
    }

    private static String endTurnProcedureQuery(String question) {
        boolean asksAfterTurn = containsAny(
                question,
                "end turn",
                "ends turn",
                "finish turn",
                "after turn",
                "after my turn",
                "结束回合",
                "结束自己的回合",
                "结束本回合",
                "完成回合",
                "回合结束");
        boolean asksForEventProcedure = containsAny(
                question,
                "draw",
                "reveal",
                "resolve",
                "alert",
                "event",
                "card",
                "抽",
                "翻",
                "结算",
                "执行",
                "警报",
                "事件",
                "牌");
        if (!asksAfterTurn || !asksForEventProcedure) return null;
        return bounded("completed turn draw reveal resolve event alert card effect next player procedure "
                + "完成回合后 从牌堆抽取事件或警报 执行并结算卡牌效果 然后下一位玩家 回合结束 警报 结算 "
                + question);
    }

    private static String roundResetQuery(String question) {
        boolean asksRoundEnd = containsAny(
                question, "end of round", "round ends", "round end", "一轮结束", "轮次结束", "回合结束", "轮结束");
        boolean asksReset = containsAny(question, "recover", "return", "reset", "回收", "归还", "回到", "重置", "拿回");
        if (!asksRoundEnd || !asksReset) return null;
        return bounded(question + " end of round recover dice only reset active pieces return students conditional "
                + "round reset state transition 一轮结束 回收骰子 学生 条件 返回 重置");
    }

    private static String deferredTurnQuery(String question) {
        boolean asksLaterTurn = containsAny(
                question, "next time", "next turn", "later turn", "下一次轮到", "下次轮到", "之后轮到");
        boolean asksAboutRemainingPieces = containsAny(
                question, "remaining dice", "unused dice", "active dice", "剩下的骰子", "剩余骰子", "未用骰子", "活跃骰子");
        if (!asksLaterTurn || !asksAboutRemainingPieces) return null;
        return bounded(question + " worked example player turn ends remaining active dice later turn spend one or more "
                + "round ends all dice spent turn sequence 示例回合 剩余骰子 下次回合 使用一颗或多颗骰子");
    }

    private static String bounded(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_QUERY_LENGTH).strip();
    }

    public record RetrievalIntent(
            String query, Set<String> sectionTypes, String currentSectionType, boolean directQuestion) {

        public RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType) {
            this(query, sectionTypes, currentSectionType, false);
        }

        public RetrievalIntent {
            if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                    || sectionTypes == null || sectionTypes.size() > MAX_SECTION_FILTERS) {
                throw new IllegalArgumentException("answer retrieval intent is invalid");
            }
            query = query.strip();
            sectionTypes = Set.copyOf(sectionTypes);
        }
    }
}
