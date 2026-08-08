package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
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
            "(?iu)\\d+(?:\\.\\d+)?\\s*(?:个?人|位|players?|people|分钟|minutes?|mins?|小时|hours?|hrs?)?");
    private static final Pattern CHINESE_CONNECTORS = Pattern.compile(
            "(?:请|帮我|推荐|找找|找一找|找|想要|想玩|想|适合|大概|大约|左右|以内|不超过|桌游|游戏|几款|一些|有没有|有没|来几款|来点|换一批|换些|再来|比较|希望|可以|最好|这次|一个|一款|的|吧|吗|呢)");
    private static final Pattern ENGLISH_CONNECTORS = Pattern.compile(
            "(?iu)\\b(?:please|recommend|suggest|find|want|looking|for|some|a|an|the|board|games?|players?|people|around|about|under|within|up|to|hours?|hrs?|minutes?|mins?|and|or|with|another|different)\\b");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern COOPERATIVE = Pattern.compile("(?iu)(?:合作|协作|\\b(?:cooperative|co-op|coop)\\b)");
    private static final Pattern TEAM = Pattern.compile("(?iu)(?:组队|团队|\\bteam(?:-based| based)?\\b)");
    private static final Pattern COMPETITIVE = Pattern.compile("(?iu)(?:对抗|竞争|\\bcompetitive\\b)");
    private static final Pattern LIGHT_WEIGHT =
            Pattern.compile("(?iu)(?:轻度|简单|轻策|\\b(?:lightweight|light game|easy to learn)\\b)");
    private static final Pattern MEDIUM_WEIGHT =
            Pattern.compile("(?iu)(?:中度|中等复杂|\\b(?:medium weight|medium complexity)\\b)");
    private static final Pattern HEAVY_WEIGHT =
            Pattern.compile("(?iu)(?:重度|烧脑|\\b(?:heavy game|heavyweight)\\b)");

    RetrievalPlan preserveUncoveredExpression(RetrievalPlan planned, String message) {
        return preserveUncoveredExpression(planned, message, RecommendationProfile.empty());
    }

    RetrievalPlan preserveUncoveredExpression(
            RetrievalPlan planned, String message, RecommendationProfile structuredProfile) {
        RetrievalPlan source = planned == null ? RetrievalPlan.empty() : planned;
        if (!source.features().isEmpty()) return source;
        String uncovered = uncoveredExpression(message, source.features(), structuredProfile);
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
        return uncoveredExpression(message, mappedFeatures, RecommendationProfile.empty());
    }

    String uncoveredExpression(
            String message, List<FeatureConstraint> mappedFeatures, RecommendationProfile structuredProfile) {
        String remaining = normalize(message);
        if (remaining.isBlank()) return "";
        for (FeatureConstraint feature : mappedFeatures == null ? List.<FeatureConstraint>of() : mappedFeatures) {
            String evidence = normalize(feature == null ? "" : feature.basedOn());
            if (!evidence.isBlank()) remaining = remaining.replace(evidence, " ");
        }
        remaining = NUMERIC_CONSTRAINT.matcher(remaining).replaceAll(" ");
        remaining = removeStructuredProfileLanguage(remaining, structuredProfile);
        remaining = CHINESE_CONNECTORS.matcher(remaining).replaceAll(" ");
        remaining = ENGLISH_CONNECTORS.matcher(remaining).replaceAll(" ");
        remaining = SEPARATORS.matcher(remaining).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (!meaningful(remaining)) return "";
        return bounded(remaining, 80);
    }

    private String removeStructuredProfileLanguage(String value, RecommendationProfile profile) {
        RecommendationProfile structured = profile == null ? RecommendationProfile.empty() : profile;
        String remaining = switch (structured.interaction()) {
            case COOPERATIVE -> COOPERATIVE.matcher(value).replaceAll(" ");
            case TEAM -> TEAM.matcher(value).replaceAll(" ");
            case COMPETITIVE -> COMPETITIVE.matcher(value).replaceAll(" ");
            case ANY -> value;
        };
        remaining = switch (structured.type()) {
            case PARTY -> removeTerms(remaining, "聚会游戏", "派对游戏", "party game");
            case FAMILY -> removeTerms(remaining, "家庭游戏", "family game");
            case STRATEGY -> removeTerms(remaining, "策略游戏", "strategy game");
            case THEMATIC -> removeTerms(remaining, "主题游戏", "thematic game");
            case WAR -> removeTerms(remaining, "战争游戏", "war game", "wargame");
            case ABSTRACT -> removeTerms(remaining, "抽象游戏", "抽象策略", "abstract game");
            case ALL, CUSTOMIZABLE, CHILDREN, EXPANSION -> remaining;
        };
        if (structured.maxWeight() == null || structured.maxWeight().signum() == 0) return remaining;
        if (structured.maxWeight().compareTo(new java.math.BigDecimal("2.3")) <= 0) {
            return LIGHT_WEIGHT.matcher(remaining).replaceAll(" ");
        }
        if (structured.maxWeight().compareTo(new java.math.BigDecimal("3.2")) <= 0) {
            return MEDIUM_WEIGHT.matcher(remaining).replaceAll(" ");
        }
        return HEAVY_WEIGHT.matcher(remaining).replaceAll(" ");
    }

    private String removeTerms(String value, String... terms) {
        String remaining = value;
        for (String term : terms) remaining = remaining.replace(term, " ");
        return remaining;
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
