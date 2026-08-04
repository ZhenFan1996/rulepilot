package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AnswerRetrievalPlanner {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_SECTION_FILTERS = 4;
    private static final int MAX_INTENTS = 5;
    private static final java.util.regex.Pattern QUESTION_PART_SEPARATOR =
            java.util.regex.Pattern.compile("[?？!！;；]+");
    private AnswerRetrievalPlanner() {}

    public static List<RetrievalIntent> plan(UnderstoodQuestion question, QuestionContext context) {
        return plan(question, context, List.of());
    }

    public static List<RetrievalIntent> plan(
            UnderstoodQuestion question, QuestionContext context, List<String> rewrittenQueries) {
        if (question == null || context == null) {
            throw new IllegalArgumentException("answer retrieval planning input is required");
        }
        Set<String> inferredSectionScope = inferredSections(question, context);
        String currentSection = inferredSectionScope.stream().findFirst().orElse(null);
        Set<String> directQuestionScope = directQuestionScope(question, currentSection);
        List<RetrievalIntent> procedureIntents =
                AnswerRetrievalProcedureIntents.plan(question.normalizedQuestion());
        List<RetrievalIntent> intents = new ArrayList<>(procedureIntents.stream()
                .filter(intent -> intent.purpose() == RetrievalPurpose.ENDGAME_RESOLUTION)
                .toList());
        List<RetrievalIntent> deferredConditionIntents = procedureIntents.stream()
                .filter(intent -> intent.purpose() == RetrievalPurpose.CONDITION_PROCEDURE)
                .toList();
        String contextualQuestion = contextualQuestion(question.normalizedQuestion(), context.previousQuestion());
        List<String> parts = questionParts(contextualQuestion);
        Set<String> learningScope = context.learningIntent() != null && currentSection != null
                ? Set.of(currentSection)
                : directQuestionScope;
        int availableBeforeSupplementary =
                Math.max(1, MAX_INTENTS - intents.size() - deferredConditionIntents.size() - 1);
        if (parts.size() == 1) {
            int rewriteBudget = Math.max(0, availableBeforeSupplementary - 1);
            addRewrittenQueries(intents, rewrittenQueries, rewriteBudget, directQuestionScope, currentSection);
            intents.add(new RetrievalIntent(
                    expandSearchTerms(question.normalizedQuestion()), learningScope, currentSection, true));
        } else {
            parts.stream().limit(availableBeforeSupplementary).forEach(part -> intents.add(new RetrievalIntent(
                    expandSearchTerms(part), learningScope, currentSection, true)));
            int rewriteBudget = Math.max(0, MAX_INTENTS - intents.size() - 1);
            addRewrittenQueries(intents, rewrittenQueries, rewriteBudget, directQuestionScope, currentSection);
        }
        deferredConditionIntents.forEach(intents::add);
        intents.add(new RetrievalIntent(
                supplementaryQuery(question, context),
                inferredSectionScope,
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
        java.util.regex.Pattern separator = AnswerEvidencePolicy.asksForCompleteList(question)
                ? java.util.regex.Pattern.compile("[?？!！;；]+|[,，、]+|\\s+(?i:and|or)\\s+|\\s+(?:以及|和|或)\\s*")
                : QUESTION_PART_SEPARATOR;
        List<String> parts = separator.splitAsStream(question)
                .map(String::strip)
                .map(part -> part.replaceFirst("^(?i:and|or)\\s+", "").strip())
                .filter(part -> part.length() >= 2)
                .distinct()
                .limit(MAX_INTENTS - 1L)
                .toList();
        return parts.isEmpty() ? List.of(question) : parts;
    }

    private static void addRewrittenQueries(
            List<RetrievalIntent> intents,
            List<String> rewrittenQueries,
            int rewriteBudget,
            Set<String> directQuestionScope,
            String currentSection) {
        if (rewrittenQueries == null || rewriteBudget <= 0) return;
        rewrittenQueries.stream()
                .map(AnswerRetrievalPlanner::bounded)
                .filter(query -> !query.isBlank())
                .distinct()
                .limit(rewriteBudget)
                .forEach(query -> intents.add(new RetrievalIntent(
                        query,
                        directQuestionScope,
                        directQuestionScope.isEmpty() ? null : currentSection)));
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
        append(query, AnswerRetrievalProcedureIntents.endgameResolutionTerms(question.normalizedQuestion()));
        append(query, learningFacets(context.learningIntent()));
        append(query, permissionFacets(question.normalizedQuestion()));
        return bounded(query.toString());
    }

    private static String permissionFacets(String question) {
        return AnswerPermissionResolver.asksForPermission(question)
                ? "permission prohibition may may not cannot allowed prerequisite exception 允许 禁止 可以 不能 条件 例外"
                : null;
    }

    private static String learningFacets(LearningIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case SIMPLIFY -> "core rule sequence must remember 核心规则 顺序 必须记住";
            case EXAMPLE -> "worked example legal sequence cost result 具体示例 合法步骤 费用 结果";
            case DEFINE -> "definition glossary terminology means refers to 定义 术语 名词 指的是 含义";
            case WHY -> "rule prerequisite consequence order 前置条件 规则后果 执行顺序";
            case EXCEPTIONS -> "restriction timing limit exception cannot 限制 时机 次数 例外 禁止";
            case SOURCE -> "exact rule clause direct source wording page 原文 条款 直接依据 页码";
            case VERIFY -> "direct rule condition timing exception contradiction 直接规则 条件 时机 例外 矛盾";
        };
    }

    private static String facets(QuestionType type) {
        return switch (type) {
            case LESSON_STEP_FOLLOW_UP -> "step prerequisite consequence exception 步骤 前置条件 结果 例外";
            case RULE_QUERY -> "rule definition timing restriction exception 规则 定义 时机 限制 例外";
            case SITUATION_QUERY -> "legal action prerequisite timing cost exception 合法行动 前置条件 时机 费用 例外";
        };
    }

    private static Set<String> inferredSections(UnderstoodQuestion question, QuestionContext context) {
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        String text = contextualQuestion(question.normalizedQuestion(), context.previousQuestion());
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
        addWhenContains(
                sections,
                text,
                "ACTIONS",
                "action",
                "play card",
                "play this card",
                "行动",
                "打出",
                "卡牌");
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
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        sections.stream().limit(MAX_SECTION_FILTERS).forEach(bounded::add);
        return Collections.unmodifiableSet(bounded);
    }

    /** Question-derived scope may narrow an executable sequence; no caller supplies a chapter hint. */
    private static Set<String> directQuestionScope(UnderstoodQuestion question, String currentSection) {
        if (currentSection == null || !asksForExecutableSequence(question.normalizedQuestion())) {
            return Set.of();
        }
        return Set.of(currentSection);
    }

    private static boolean asksForExecutableSequence(String question) {
        return containsAny(
                question,
                "step", "steps", "order", "sequence", "walk through", "first", "then",
                "步骤", "顺序", "逐步", "先", "然后", "依次", "怎么摆", "如何摆");
    }

    private static void addWhenContains(
            Set<String> sections, String text, String section, String... indicators) {
        if (containsAny(text, indicators)) {
            sections.add(section);
        }
    }

    static boolean containsAny(String text, String... indicators) {
        return Arrays.stream(indicators).anyMatch(text::contains);
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank() && target.indexOf(value) < 0) {
            target.append(' ').append(value.strip());
        }
    }

    static String bounded(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_QUERY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_QUERY_LENGTH).strip();
    }

    public enum RetrievalPurpose {
        GENERAL,
        ENDGAME_RESOLUTION,
        CONDITION_PROCEDURE
    }

    public record RetrievalIntent(
            String query,
            Set<String> sectionTypes,
            String currentSectionType,
            boolean directQuestion,
            RetrievalPurpose purpose) {

        public RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType) {
            this(query, sectionTypes, currentSectionType, false, RetrievalPurpose.GENERAL);
        }

        public RetrievalIntent(String query, Set<String> sectionTypes, String currentSectionType, boolean directQuestion) {
            this(query, sectionTypes, currentSectionType, directQuestion, RetrievalPurpose.GENERAL);
        }

        public RetrievalIntent {
            if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH
                    || sectionTypes == null || sectionTypes.size() > MAX_SECTION_FILTERS || purpose == null) {
                throw new IllegalArgumentException("answer retrieval intent is invalid");
            }
            query = query.strip();
            sectionTypes = Set.copyOf(sectionTypes);
        }
    }
}
