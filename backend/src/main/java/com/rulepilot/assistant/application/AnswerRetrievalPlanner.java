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
            java.util.regex.Pattern.compile("[?？!！;；,，]+");
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
        List<RetrievalIntent> intents = new ArrayList<>();
        String contextualQuestion = contextualQuestion(question.normalizedQuestion(), context.previousQuestion());
        List<String> parts = questionParts(contextualQuestion);
        Set<String> learningScope = context.learningIntent() != null && currentSection != null
                ? Set.of(currentSection)
                : Set.of();
        int questionIntentBudget = Math.max(1, MAX_INTENTS - intents.size() - 1);
        if (parts.size() == 1) {
            intents.add(new RetrievalIntent(
                    expandSearchTerms(question.normalizedQuestion()), learningScope, currentSection));
        } else {
            parts.stream().limit(questionIntentBudget).forEach(part -> intents.add(new RetrievalIntent(
                    expandSearchTerms(part), learningScope, currentSection)));
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
        if (containsAny(normalized, "END", "结束")) return "END_CONDITIONS";
        if (containsAny(normalized, "TURN", "ROUND", "PASS", "FIRST_ROUND", "回合", "轮次")) {
            return "ROUND_STRUCTURE";
        }
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
