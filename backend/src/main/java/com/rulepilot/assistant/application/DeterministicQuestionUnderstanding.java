package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DeterministicQuestionUnderstanding implements QuestionUnderstanding {

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*");
    private static final Pattern SITUATION = Pattern.compile(
            "\\b(can|may|could) i\\b|\\b(on my turn|in my hand|i have|we have|my token|my card)\\b|"
                    + "我的(回合|手牌|卡牌|棋子)|当前局面|此时");
    private static final Pattern STEP_REFERENCE = Pattern.compile(
            "\\b(this|that|previous|next) (step|part|section)\\b|\\bwhy (do|did) we\\b|"
                    + "这一步|上一步|下一步|刚才|这个步骤|这里为什么");
    private static final Pattern VAGUE_REFERENCE = Pattern.compile(
            "\\b(this|that|it)\\b|这个|这样|它|那(?:我)?|再做一次|再来一次|还能再|还可以再|上述|前面");
    private static final Pattern UNRESOLVED_FOLLOW_UP = Pattern.compile(
            "\\b(can|could) i (do|take|play) (it|that) again\\b|那(?:我)?|再做一次|再来一次|还能再|还可以再");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "am", "be", "do", "did", "does", "what", "when", "where",
            "why", "how", "who", "which", "can", "may", "could", "should", "would", "i", "we", "you",
            "my", "our", "your", "this", "that", "it", "to", "of", "in", "on", "at", "for", "from",
            "with", "and", "or", "if", "then", "during", "before", "after", "have", "has", "had");
    private static final List<String> CHINESE_TERMS = List.of(
            "回合", "阶段", "行动", "计分", "得分", "胜利点", "手牌", "卡牌", "棋子", "资源", "扩展", "平局", "设置");

    @Override
    public UnderstoodQuestion understand(String question, QuestionContext context) {
        if (question == null || question.isBlank() || context == null) {
            throw new IllegalArgumentException("question and context are required");
        }
        String original = SPACE.matcher(question.strip()).replaceAll(" ");
        String normalized = original.toLowerCase(Locale.ROOT);
        QuestionType type = classify(normalized, context);
        Set<MissingQuestionContext> missing = missingContext(normalized, type, context);
        return new UnderstoodQuestion(
                context.documentVersionId(),
                original,
                normalized,
                type,
                extractTerms(normalized),
                missing,
                context.currentLessonSection());
    }

    private QuestionType classify(String question, QuestionContext context) {
        if (context.previousQuestion() != null
                && context.currentLessonSection() != null
                && VAGUE_REFERENCE.matcher(question).find()) {
            return QuestionType.LESSON_STEP_FOLLOW_UP;
        }
        if (context.previousQuestion() == null
                && context.currentLessonSection() == null
                && UNRESOLVED_FOLLOW_UP.matcher(question).find()) {
            return QuestionType.SITUATION_QUERY;
        }
        if (SITUATION.matcher(question).find()) {
            return QuestionType.SITUATION_QUERY;
        }
        if (context.currentLessonSection() != null || STEP_REFERENCE.matcher(question).find()) {
            return QuestionType.LESSON_STEP_FOLLOW_UP;
        }
        return QuestionType.RULE_QUERY;
    }

    private Set<MissingQuestionContext> missingContext(
            String question, QuestionType type, QuestionContext context) {
        Set<MissingQuestionContext> missing = new LinkedHashSet<>();
        if (type == QuestionType.LESSON_STEP_FOLLOW_UP && context.currentLessonSection() == null) {
            missing.add(MissingQuestionContext.CURRENT_LESSON_SECTION);
        }
        if (type == QuestionType.SITUATION_QUERY) {
            if (context.gamePhase() == null) {
                missing.add(MissingQuestionContext.GAME_PHASE);
            }
            if (VAGUE_REFERENCE.matcher(question).find()
                    && context.currentLessonSection() == null
                    && context.previousQuestion() == null) {
                missing.add(MissingQuestionContext.SITUATION_DETAILS);
            }
        }
        if ((question.contains("expansion") || question.contains("扩展")) && context.activeExpansions().isEmpty()) {
            missing.add(MissingQuestionContext.EXPANSION_SELECTION);
        }
        return Set.copyOf(missing);
    }

    private List<String> extractTerms(String question) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : CHINESE_TERMS) {
            if (question.contains(term)) {
                terms.add(term);
            }
        }
        Matcher matcher = WORD.matcher(question);
        while (matcher.find() && terms.size() < 12) {
            String term = matcher.group();
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }
}
