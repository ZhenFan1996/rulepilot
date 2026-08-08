package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BoardGameRecommendationAdvisor.PreferencePatch;
import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.ClarificationOption;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
class BoardGamePreferenceDialogue {

    private static final Pattern PLAYERS_ZH = Pattern.compile("(?<!\\d)(1[0-2]|[1-9])\\s*(?:个?人|位)");
    private static final Pattern PLAYERS_EN = Pattern.compile("(?i)(?<!\\d)(1[0-2]|[1-9])\\s*(?:players?|people)");
    private static final Pattern MINUTES = Pattern.compile("(?i)(?<!\\d)(1[5-9]|[2-9]\\d|[1-5]\\d{2}|600)\\s*(?:分钟|mins?|minutes?)");
    private static final Pattern HOURS = Pattern.compile("(?i)(?<![\\d.])(0\\.5|1(?:\\.5)?|2(?:\\.5)?|3|4|5|6|7|8|9|10)\\s*(?:小时|hours?|hrs?)");
    private static final int MAX_MESSAGE_LENGTH = 500;

    ResolvedTurn resolve(
            RecommendationProfile input,
            String inputMessage,
            PreferencePatch advisedPatch,
            String locale) {
        RecommendationProfile profile = validatedProfile(input == null ? RecommendationProfile.empty() : input);
        String message = normalizedMessage(inputMessage);
        if (!message.isBlank()) {
            PreferencePatch patch = deterministicPatch(message);
            if (advisedPatch != null) patch = preferDeterministic(patch, advisedPatch);
            profile = apply(profile, patch);
        }
        return new ResolvedTurn(profile, nextClarification(profile, locale), hasPreferenceSignal(profile));
    }

    private RecommendationProfile validatedProfile(RecommendationProfile profile) {
        if (profile.players() != null && (profile.players() < 1 || profile.players() > 12)) {
            throw new IllegalArgumentException("players must be between 1 and 12");
        }
        if (profile.maxMinutes() != null && (profile.maxMinutes() < 0 || profile.maxMinutes() > 600)) {
            throw new IllegalArgumentException("maxMinutes must be between 0 and 600");
        }
        if (profile.maxWeight() != null
                && (profile.maxWeight().compareTo(BigDecimal.ZERO) < 0
                        || profile.maxWeight().compareTo(BigDecimal.valueOf(5)) > 0)) {
            throw new IllegalArgumentException("maxWeight must be between 0 and 5");
        }
        return new RecommendationProfile(
                profile.players(),
                profile.maxMinutes(),
                profile.maxWeight(),
                profile.type() == null ? GameType.ALL : profile.type(),
                profile.interaction() == null ? InteractionPreference.ANY : profile.interaction());
    }

    private String normalizedMessage(String value) {
        String message = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("recommendation message must contain at most 500 characters");
        }
        return message;
    }

    private RecommendationProfile apply(RecommendationProfile profile, PreferencePatch patch) {
        if (patch == null) return profile;
        return validatedProfile(new RecommendationProfile(
                patch.players() == null ? profile.players() : patch.players(),
                patch.maxMinutes() == null ? profile.maxMinutes() : patch.maxMinutes(),
                patch.maxWeight() == null ? profile.maxWeight() : patch.maxWeight(),
                patch.type() == null ? profile.type() : patch.type(),
                patch.interaction() == null ? profile.interaction() : patch.interaction()));
    }

    private PreferencePatch preferDeterministic(PreferencePatch deterministic, PreferencePatch interpreted) {
        return new PreferencePatch(
                deterministic.players() == null ? interpreted.players() : deterministic.players(),
                deterministic.maxMinutes() == null ? interpreted.maxMinutes() : deterministic.maxMinutes(),
                deterministic.maxWeight() == null ? interpreted.maxWeight() : deterministic.maxWeight(),
                deterministic.type(),
                deterministic.interaction());
    }

    private PreferencePatch deterministicPatch(String message) {
        String normalized = message.strip().toLowerCase(Locale.ROOT);
        Integer players = firstInteger(PLAYERS_ZH, normalized);
        if (players == null) players = firstInteger(PLAYERS_EN, normalized);
        Integer minutes = firstInteger(MINUTES, normalized);
        if (minutes == null) {
            Matcher hours = HOURS.matcher(normalized);
            if (hours.find()) minutes = (int) Math.round(Double.parseDouble(hours.group(1)) * 60);
        }
        if (containsAny(normalized, "时长不限", "时间不限", "no time limit", "any duration")) minutes = 0;

        BigDecimal weight = null;
        if (containsAny(normalized, "轻度", "简单", "轻策", "lightweight", "light game", "easy to learn")) {
            weight = new BigDecimal("2.3");
        } else if (containsAny(normalized, "中度", "中等复杂", "medium weight", "medium complexity")) {
            weight = new BigDecimal("3.2");
        } else if (containsAny(normalized, "重度", "烧脑", "heavy game", "heavyweight")) {
            weight = new BigDecimal("4.5");
        } else if (containsAny(normalized, "复杂度不限", "难度不限", "any complexity")) {
            weight = BigDecimal.ZERO;
        }

        GameType type = null;
        if (containsAny(normalized, "聚会游戏", "派对游戏", "party game")) type = GameType.PARTY;
        else if (containsAny(normalized, "家庭游戏", "family game")) type = GameType.FAMILY;
        else if (containsAny(normalized, "策略游戏", "strategy game")) type = GameType.STRATEGY;
        else if (containsAny(normalized, "主题游戏", "thematic game")) type = GameType.THEMATIC;
        else if (containsAny(normalized, "战争游戏", "war game", "wargame")) type = GameType.WAR;
        else if (containsAny(normalized, "抽象游戏", "抽象策略", "abstract game")) type = GameType.ABSTRACT;

        InteractionPreference interaction = null;
        if (containsAny(normalized, "合作", "cooperative", "co-op", "coop")) interaction = InteractionPreference.COOPERATIVE;
        else if (containsAny(normalized, "组队", "团队", "team-based", "team based")) interaction = InteractionPreference.TEAM;
        else if (containsAny(normalized, "对抗", "竞争", "competitive")) interaction = InteractionPreference.COMPETITIVE;
        else if (containsAny(normalized, "互动不限", "any interaction")) interaction = InteractionPreference.ANY;
        return new PreferencePatch(players, minutes, weight, type, interaction);
    }

    private Clarification nextClarification(RecommendationProfile profile, String locale) {
        boolean chinese = "zh-CN".equals(locale);
        if (profile.players() == null) {
            return new Clarification(
                    PreferenceField.PLAYERS,
                    chinese ? "这次准备几个人一起玩？" : "How many people will play?",
                    List.of(
                            option("1", chinese ? "1 人" : "1 player"),
                            option("2", chinese ? "2 人" : "2 players"),
                            option("3", chinese ? "3 人" : "3 players"),
                            option("4", chinese ? "4 人" : "4 players"),
                            option("5", chinese ? "5 人" : "5 players"),
                            option("6", chinese ? "6 人以上" : "6+ players")));
        }
        if (profile.maxMinutes() == null) {
            return new Clarification(
                    PreferenceField.DURATION,
                    chinese ? "你们愿意为一局留出多长时间？" : "How much time do you have for one game?",
                    List.of(
                            option("30", chinese ? "30 分钟内" : "Up to 30 min"),
                            option("60", chinese ? "1 小时内" : "Up to 1 hour"),
                            option("90", chinese ? "90 分钟内" : "Up to 90 min"),
                            option("120", chinese ? "2 小时内" : "Up to 2 hours"),
                            option("180", chinese ? "3 小时内" : "Up to 3 hours"),
                            option("0", chinese ? "时长不限" : "No time limit")));
        }
        if (profile.maxWeight() == null) {
            return new Clarification(
                    PreferenceField.COMPLEXITY,
                    chinese ? "这次想要多复杂？" : "How complex should it be?",
                    List.of(
                            option("2.3", chinese ? "轻松上手" : "Easy to learn"),
                            option("3.2", chinese ? "中等策略" : "Medium strategy"),
                            option("4.5", chinese ? "重度烧脑" : "Deep and demanding"),
                            option("0", chinese ? "复杂度不限" : "Any complexity")));
        }
        return null;
    }

    private boolean hasPreferenceSignal(RecommendationProfile profile) {
        return profile.players() != null
                || profile.maxMinutes() != null
                || profile.maxWeight() != null
                || profile.type() != GameType.ALL
                || profile.interaction() != InteractionPreference.ANY;
    }

    private ClarificationOption option(String value, String label) {
        return new ClarificationOption(value, label);
    }

    private Integer firstInteger(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    record ResolvedTurn(RecommendationProfile profile, Clarification clarification, boolean hasPreferenceSignal) {}
}
