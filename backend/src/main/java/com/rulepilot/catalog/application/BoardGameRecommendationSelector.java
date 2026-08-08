package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Choice;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Slate;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.catalog.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
class BoardGameRecommendationSelector {

    private final BoardGameRecommendationProperties properties;

    BoardGameRecommendationSelector(BoardGameRecommendationProperties properties) {
        this.properties = properties;
    }

    CandidatePool prepare(List<BrowseGame> source, RecommendationProfile profile, List<Integer> excludedBggIds) {
        int candidatesEvaluated = (int) source.stream().filter(game -> game.details() != null).count();
        if (!source.isEmpty() && candidatesEvaluated == 0) {
            return new CandidatePool(SelectionStatus.NO_DETAILS, 0, List.of());
        }
        List<BrowseGame> eligible = source.stream()
                .filter(game -> !excludedBggIds.contains(game.ranked().bggId()))
                .filter(game -> eligible(game.details(), profile))
                .sorted(candidateComparator(profile))
                .toList();
        return new CandidatePool(
                eligible.isEmpty() ? SelectionStatus.NO_MATCH : SelectionStatus.READY,
                candidatesEvaluated,
                eligible);
    }

    List<Candidate> advisorCandidates(CandidatePool pool) {
        return pool.candidates().stream().map(this::candidate).toList();
    }

    List<RecommendedGame> fallback(CandidatePool pool, RecommendationProfile profile, boolean chinese) {
        return diversify(pool.candidates()).stream()
                .map(game -> factsOnly(game, profile, chinese))
                .toList();
    }

    List<RecommendedGame> fromSlate(
            CandidatePool pool,
            Slate slate,
            RecommendationProfile profile,
            boolean chinese) {
        Map<Integer, BrowseGame> candidates = pool.candidates().stream().collect(java.util.stream.Collectors.toMap(
                game -> game.ranked().bggId(), Function.identity()));
        List<RecommendedGame> result = new ArrayList<>();
        for (Choice choice : slate.choices()) {
            BrowseGame game = candidates.get(choice.bggId());
            if (game == null) return List.of();
            RecommendedGame facts = factsOnly(game, profile, chinese);
            List<RecommendationReason> reasons = new ArrayList<>(facts.reasons());
            choice.preferenceReasons().forEach(text -> reasons.add(new RecommendationReason(
                    ReasonKind.PREFERENCE_INFERENCE, text, List.of())));
            choice.researchedReasons().forEach(reason -> reasons.add(new RecommendationReason(
                    ReasonKind.WEB_RESEARCH, reason.text(), reason.sourceIndexes())));
            List<String> matches = new ArrayList<>(facts.matches());
            matches.addAll(choice.preferenceReasons());
            List<String> tradeoffs = new ArrayList<>(facts.tradeoffs());
            tradeoffs.addAll(choice.tradeoffs());
            result.add(new RecommendedGame(game, matches, tradeoffs, reasons));
        }
        return List.copyOf(result);
    }

    private Candidate candidate(BrowseGame game) {
        DiscoveryGame details = game.details();
        return new Candidate(
                game.ranked().bggId(),
                game.ranked().sourceName(),
                game.ranked().publicationYear(),
                game.ranked().overallRank(),
                game.ranked().averageRating(),
                details.averageWeight(),
                details.minPlayers(),
                details.maxPlayers(),
                details.playingTimeMinutes(),
                details.minimumPlayTimeMinutes(),
                details.maximumPlayTimeMinutes(),
                details.minimumAge(),
                details.suggestedMinimumAge(),
                details.bestWith(),
                details.recommendedWith(),
                details.languageDependenceLevel(),
                details.weightVotes(),
                details.categories(),
                details.mechanics(),
                details.families(),
                details.designers(),
                details.publishers());
    }

    private Comparator<BrowseGame> candidateComparator(RecommendationProfile profile) {
        return Comparator.comparingInt((BrowseGame game) -> playerFit(game.details(), profile.players()))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                                (BrowseGame game) -> interactionFit(game.details(), profile.interaction()))
                        .reversed())
                .thenComparing(
                        (BrowseGame game) -> game.ranked().bayesAverage(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        (BrowseGame game) -> game.ranked().overallRank(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt(
                                (BrowseGame game) -> game.ranked().usersRated())
                        .reversed())
                .thenComparingInt(game -> game.ranked().bggId());
    }

    private int playerFit(DiscoveryGame game, Integer players) {
        if (game == null || players == null) return 1;
        if (includesPlayerCount(game.bestWith(), players)) return 3;
        if (includesPlayerCount(game.recommendedWith(), players)) return 2;
        return 1;
    }

    private boolean includesPlayerCount(String summary, int players) {
        String normalized = summary.toLowerCase(Locale.ROOT).replace('\u2013', '-').replace('\u2014', '-');
        if (normalized.matches(".*(?<!\\d)" + players + "(?!\\d).*$")) return true;
        java.util.regex.Matcher ranges = java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2})\\s*-\\s*(\\d{1,2})(?!\\d)")
                .matcher(normalized);
        while (ranges.find()) {
            int minimum = Integer.parseInt(ranges.group(1));
            int maximum = Integer.parseInt(ranges.group(2));
            if (players >= minimum && players <= maximum) return true;
        }
        return false;
    }

    private boolean eligible(DiscoveryGame game, RecommendationProfile profile) {
        if (game == null) return false;
        if (profile.players() != null
                && (game.minPlayers() == null
                        || game.maxPlayers() == null
                        || game.minPlayers() > profile.players()
                        || game.maxPlayers() < profile.players())) return false;
        Integer maximumMinutes = game.maximumPlayTimeMinutes() == null
                ? game.playingTimeMinutes()
                : game.maximumPlayTimeMinutes();
        if (profile.maxMinutes() != null && profile.maxMinutes() > 0
                && (maximumMinutes == null || maximumMinutes > profile.maxMinutes())) return false;
        return profile.maxWeight() == null
                || profile.maxWeight().compareTo(BigDecimal.ZERO) == 0
                || (game.averageWeight() != null && game.averageWeight().compareTo(profile.maxWeight()) <= 0);
    }

    private int interactionFit(DiscoveryGame game, InteractionPreference preference) {
        if (preference == InteractionPreference.ANY) return 1;
        Set<String> mechanics = game.mechanics().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        boolean cooperative = mechanics.contains("cooperative game");
        boolean team = mechanics.contains("team-based game") || mechanics.contains("team based game");
        return switch (preference) {
            case COOPERATIVE -> cooperative ? 2 : 0;
            case TEAM -> team ? 2 : 0;
            case COMPETITIVE -> !cooperative && !team ? 2 : 0;
            case ANY -> 1;
        };
    }

    private List<BrowseGame> diversify(List<BrowseGame> ranked) {
        List<BrowseGame> remaining = new ArrayList<>(ranked);
        List<BrowseGame> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < properties.resultCount()) {
            BrowseGame next = remaining.stream()
                    .filter(candidate -> selected.stream().allMatch(chosen -> overlap(candidate, chosen)
                            <= properties.diversityOverlapLimit().doubleValue()))
                    .findFirst()
                    .orElse(remaining.getFirst());
            selected.add(next);
            remaining.remove(next);
        }
        return List.copyOf(selected);
    }

    private double overlap(BrowseGame left, BrowseGame right) {
        Set<String> leftTerms = taxonomy(left.details());
        Set<String> rightTerms = taxonomy(right.details());
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) return 0;
        Set<String> intersection = new java.util.HashSet<>(leftTerms);
        intersection.retainAll(rightTerms);
        Set<String> union = new java.util.HashSet<>(leftTerms);
        union.addAll(rightTerms);
        return (double) intersection.size() / union.size();
    }

    private Set<String> taxonomy(DiscoveryGame game) {
        if (game == null) return Set.of();
        return java.util.stream.Stream.of(game.categories(), game.mechanics(), game.families())
                .flatMap(List::stream)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private RecommendedGame factsOnly(BrowseGame game, RecommendationProfile profile, boolean chinese) {
        DiscoveryGame details = game.details();
        List<String> matches = new ArrayList<>();
        List<String> tradeoffs = new ArrayList<>();
        if (profile.players() != null) {
            matches.add(chinese ? "支持 " + profile.players() + " 人游玩" : "Supports " + profile.players() + " players");
            if (!details.bestWith().isBlank() && playerFit(details, profile.players()) == 3) {
                matches.add(chinese ? "BGG 玩家投票认为这个人数最合适" : "BGG player votes mark this count as best");
            }
        }
        if (profile.maxMinutes() != null && profile.maxMinutes() > 0) {
            Integer minutes = details.maximumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.maximumPlayTimeMinutes();
            matches.add(chinese
                    ? minutes + " 分钟以内，不超过你的时长上限"
                    : "Up to " + minutes + " minutes, within your limit");
        }
        if (profile.maxWeight() != null && profile.maxWeight().compareTo(BigDecimal.ZERO) > 0) {
            matches.add(chinese
                    ? "BGG 复杂度 " + oneDecimal(details.averageWeight()) + " / 5，在你的上限内"
                    : "BGG complexity " + oneDecimal(details.averageWeight()) + " / 5 is within your limit");
        }
        interactionExplanation(details, profile.interaction(), chinese, matches, tradeoffs);
        matches.add(rankSignal(game, chinese));
        List<RecommendationReason> reasons = matches.stream()
                .map(text -> new RecommendationReason(ReasonKind.BGG_FACT, text, List.of()))
                .toList();
        return new RecommendedGame(game, matches, tradeoffs, reasons);
    }

    private void interactionExplanation(
            DiscoveryGame game,
            InteractionPreference preference,
            boolean chinese,
            List<String> matches,
            List<String> tradeoffs) {
        if (preference == InteractionPreference.ANY) return;
        if (interactionFit(game, preference) == 2) {
            String value = switch (preference) {
                case COOPERATIVE -> chinese ? "BGG 标注了合作游戏机制" : "BGG lists the Cooperative Game mechanism";
                case TEAM -> chinese ? "BGG 标注了团队游戏机制" : "BGG lists the Team-Based Game mechanism";
                case COMPETITIVE -> chinese ? "BGG 机制资料未标为合作或团队游戏" : "BGG mechanics do not label it cooperative or team-based";
                case ANY -> "";
            };
            if (!value.isBlank()) matches.add(value);
        } else {
            tradeoffs.add(chinese
                    ? "互动方式没有完全命中你的偏好，选择前可打开详情确认"
                    : "Its interaction style is not an exact match; inspect the details before choosing");
        }
    }

    private String rankSignal(BrowseGame game, boolean chinese) {
        Integer overallRank = game.ranked().overallRank();
        if (overallRank != null) return chinese ? "BGG 总榜第 " + overallRank + " 名" : "BGG overall rank #" + overallRank;
        return chinese ? "按 BGG Geek 评分和评分人数进入候选" : "Selected by BGG Geek rating and rating volume";
    }

    private String oneDecimal(BigDecimal value) {
        return value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    record CandidatePool(SelectionStatus status, int candidatesEvaluated, List<BrowseGame> candidates) {
        CandidatePool {
            candidates = List.copyOf(candidates);
        }
    }

    enum SelectionStatus {
        READY,
        NO_DETAILS,
        NO_MATCH
    }
}
