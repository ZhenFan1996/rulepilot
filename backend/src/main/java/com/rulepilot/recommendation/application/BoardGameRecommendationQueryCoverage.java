package com.rulepilot.recommendation.application;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Preserves an explicit qualitative request when model planning has not mapped it
 * to a canonical BGG field. It does not interpret the phrase as a game fact; the
 * phrase can only trigger bounded candidate discovery and subsequent BGG lookup.
 */
@Component
@Profile("!test")
class BoardGameRecommendationQueryCoverage {

    private static final Pattern NUMERIC_CONSTRAINT = Pattern.compile(
            "(?iu)\\d+(?:\\.\\d+)?\\s*(?:个?人|位|players?|people|分钟|mins?|minutes?|小时|hours?|hrs?)?");
    private static final Pattern CHINESE_CONNECTORS = Pattern.compile(
            "(?:请|帮我|推荐|找找|找一找|找|想要|想玩|想|适合|大概|大约|左右|以内|不超过|桌游|游戏|几款|一些|有没有|有没|来几款|来点|换一批|换些|再来|比较|希望|可以|最好|这次|一个|一款|的|吧|吗|呢)");
    private static final Pattern ENGLISH_CONNECTORS = Pattern.compile(
            "(?iu)\\b(?:please|recommend|suggest|find|want|looking|for|some|a|an|the|board|games?|players?|people|around|about|under|within|up|to|hours?|hrs?|minutes?|mins?|and|or|with|another|different)\\b");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    RetrievalPlan preserveUncoveredExpression(RetrievalPlan planned, String message) {
        RetrievalPlan source = planned == null ? RetrievalPlan.empty() : planned;
        if (!source.features().isEmpty()) return source;
        String uncovered = uncoveredExpression(message, source.features());
        if (uncovered.isBlank()) return source;

        List<FeatureConstraint> features = new ArrayList<>(source.features());
        features.add(new FeatureConstraint(
                uncovered,
                FeatureMode.REQUIRED,
                FeatureSource.USER_EXPRESSION,
                bounded(message == null ? "" : message.strip(), 120)));
        return new RetrievalPlan(source.candidateTypes(), features.stream().limit(8).toList(), true);
    }

    String uncoveredExpression(String message, List<FeatureConstraint> mappedFeatures) {
        String remaining = normalize(message);
        if (remaining.isBlank()) return "";
        for (FeatureConstraint feature : mappedFeatures == null ? List.<FeatureConstraint>of() : mappedFeatures) {
            String evidence = normalize(feature == null ? "" : feature.basedOn());
            if (!evidence.isBlank()) remaining = remaining.replace(evidence, " ");
        }
        remaining = NUMERIC_CONSTRAINT.matcher(remaining).replaceAll(" ");
        remaining = CHINESE_CONNECTORS.matcher(remaining).replaceAll(" ");
        remaining = ENGLISH_CONNECTORS.matcher(remaining).replaceAll(" ");
        remaining = SEPARATORS.matcher(remaining).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (!meaningful(remaining)) return "";
        return bounded(remaining, 80);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }

    private boolean meaningful(String value) {
        long han = value.codePoints().filter(this::isHan).count();
        if (han >= 2) return true;
        return java.util.Arrays.stream(value.split(" "))
                .anyMatch(token -> token.codePoints().allMatch(Character::isLetter) && token.length() >= 3);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum).strip();
    }
}
