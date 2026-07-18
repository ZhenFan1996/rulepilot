package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_SECTION_FILTERS = 4;
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
        if (question == null || context == null) {
            throw new IllegalArgumentException("answer retrieval planning input is required");
        }
        String currentSection = knownSection(context.currentLessonSection());
        return List.of(
                new RetrievalIntent(bounded(question.normalizedQuestion()), Set.of(), currentSection),
                new RetrievalIntent(
                        supplementaryQuery(question, context),
                        inferredSections(question, currentSection),
                        currentSection));
    }

    private static String supplementaryQuery(UnderstoodQuestion question, QuestionContext context) {
        StringBuilder query = new StringBuilder(question.normalizedQuestion());
        if (!question.terms().isEmpty()) {
            append(query, String.join(" ", question.terms()));
        }
        append(query, facets(question.type()));
        if (context.currentLessonSection() != null) {
            append(query, context.currentLessonSection().replace('_', ' '));
        }
        if (context.gamePhase() != null) {
            append(query, context.gamePhase().replace('_', ' '));
        }
        if (context.playerCount() != null) {
            append(query, context.playerCount() + " players " + context.playerCount() + "人");
        }
        return bounded(query.toString());
    }

    private static String facets(QuestionType type) {
        return switch (type) {
            case LESSON_STEP_FOLLOW_UP -> "step prerequisite consequence exception 步骤 前置条件 结果 例外";
            case RULE_QUERY -> "rule definition timing restriction exception 规则 定义 时机 限制 例外";
            case SITUATION_QUERY -> "legal action prerequisite timing cost exception 合法行动 前置条件 时机 费用 例外";
        };
    }

    private static Set<String> inferredSections(UnderstoodQuestion question, String currentSection) {
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        if (currentSection != null) {
            sections.add(currentSection);
        }
        String text = question.normalizedQuestion();
        addWhenContains(sections, text, "SETUP", "setup", "starting", "开局", "设置", "布置");
        addWhenContains(sections, text, "TIE_BREAKERS", "tie", "tied", "平局", "同分");
        addWhenContains(sections, text, "SCORING", "score", "point", "scoring", "计分", "得分", "分数");
        addWhenContains(sections, text, "END_CONDITIONS", "game end", "ending", "结束条件", "游戏结束");
        addWhenContains(sections, text, "ACTIONS", "action", "play card", "行动", "打出", "卡牌");
        addWhenContains(sections, text, "PHASES", "phase", "阶段");
        addWhenContains(sections, text, "ROUND_STRUCTURE", "round", "turn order", "轮次", "回合顺序");
        addWhenContains(sections, text, "COMPONENTS", "component", "piece", "组件", "配件", "棋子");
        addWhenContains(sections, text, "OBJECTIVE", "objective", "win", "目标", "获胜", "胜利");
        return sections.stream().limit(MAX_SECTION_FILTERS).collect(Collectors.toUnmodifiableSet());
    }

    private static void addWhenContains(
            Set<String> sections, String text, String section, String... indicators) {
        if (Arrays.stream(indicators).anyMatch(text::contains)) {
            sections.add(section);
        }
    }

    private static String knownSection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return KNOWN_SECTIONS.contains(normalized) ? normalized : null;
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank() && target.indexOf(value) < 0) {
            target.append(' ').append(value.strip());
        }
    }

    private static String bounded(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_QUERY_LENGTH).strip();
    }

    public record RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType) {
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
