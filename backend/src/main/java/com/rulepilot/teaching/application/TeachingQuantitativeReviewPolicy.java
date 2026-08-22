package com.rulepilot.teaching.application;

import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.RuleFactDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Identifies source relationships whose quantity or legal boundary needs independent semantic review. */
final class TeachingQuantitativeReviewPolicy {

    private static final Pattern NUMERICAL_MARKER = Pattern.compile(
            "[\\p{Nd}]|[+×*÷/=<>≤≥%％]");
    private static final Pattern CHINESE_AMOUNT = Pattern.compile(
            "(?<![上下前后])[零〇一二三四五六七八九十百千万亿两半]+\\s*"
                    + "(分|点|个|张|枚|块|轮|回合|次|倍|格|金币|资源|胜利点)");
    private static final Pattern ENGLISH_RELATION = Pattern.compile(
            "\\b(each|per|times|total|score|scoring|points?|multiplier|divisor|cap|maximum|minimum|"
                    + "at most|at least|round(?:ed|ing)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<RuleFactRole> HIGH_IMPACT_FACT_ROLES = Set.of(
            RuleFactRole.COST_OR_GAIN,
            RuleFactRole.TIMING,
            RuleFactRole.LIMIT,
            RuleFactRole.EXCEPTION);

    private TeachingQuantitativeReviewPolicy() {}

    static boolean requiresCompleteReviewEvidence(
            TeachingPlan.PlannedSection planned,
            SectionDraft draft) {
        if (planned.coverageTags().contains(TeachingSourceCoverageContract.roleTag(SourceCoverageRole.SCORING))) {
            return true;
        }
        if (draft.steps().stream().anyMatch(step -> step.kind() == TeachingMove.LEDGER
                || step.kind() == TeachingMove.LIMIT
                || step.ruleFacts().stream().map(RuleFactDraft::role).anyMatch(HIGH_IMPACT_FACT_ROLES::contains))) {
            return true;
        }
        return playerFacingText(draft).anyMatch(TeachingQuantitativeReviewPolicy::containsQuantitativeRelation);
    }

    private static Stream<String> playerFacingText(SectionDraft draft) {
        return Stream.concat(
                Stream.of(draft.title(), draft.visualCaption()),
                draft.steps().stream().flatMap(step -> Stream.concat(
                        Stream.of(step.heading(), step.text()),
                        step.ruleFacts().stream().map(RuleFactDraft::text))));
    }

    private static boolean containsQuantitativeRelation(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return NUMERICAL_MARKER.matcher(value).find()
                || CHINESE_AMOUNT.matcher(value).find()
                || ENGLISH_RELATION.matcher(value).find()
                || normalized.contains("每")
                || normalized.contains("计分")
                || normalized.contains("得分")
                || normalized.contains("合计")
                || normalized.contains("总计")
                || normalized.contains("倍")
                || normalized.contains("上限")
                || normalized.contains("下限")
                || normalized.contains("最多")
                || normalized.contains("至少")
                || normalized.contains("取整");
    }
}
