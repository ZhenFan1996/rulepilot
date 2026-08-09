package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Application-owned hard gates and factual presentation for Agent-selected games. */
@Component
@Profile("!test")
class BoardGameRecommendationSelector {

    private final BoardGameRecommendationProperties properties;

    BoardGameRecommendationSelector(BoardGameRecommendationProperties properties) {
        this.properties = properties;
    }

    List<Game> eligible(
            List<Game> source,
            RecommendationProfile profile,
            Set<Integer> excludedBggIds,
            int maximum) {
        List<Game> allowed = source.stream()
                .filter(game -> game != null && game.details() != null)
                .filter(game -> !excludedBggIds.contains(game.ranking().bggId()))
                .filter(game -> eligible(game, profile))
                .toList();
        return diversify(allowed, maximum);
    }

    boolean eligible(Game game, RecommendationProfile profile) {
        if (game == null || game.details() == null) return false;
        Details details = game.details();
        if (profile.players() != null
                && (details.minPlayers() == null
                        || details.maxPlayers() == null
                        || details.minPlayers() > profile.players()
                        || details.maxPlayers() < profile.players())) return false;
        Integer maximumMinutes = details.maximumPlayTimeMinutes() == null
                ? details.playingTimeMinutes()
                : details.maximumPlayTimeMinutes();
        if (profile.maxMinutes() != null
                && profile.maxMinutes() > 0
                && (maximumMinutes == null || maximumMinutes > profile.maxMinutes())) return false;
        if (profile.maxWeight() != null
                && profile.maxWeight().compareTo(BigDecimal.ZERO) > 0
                && (details.averageWeight() == null
                        || details.averageWeight().compareTo(profile.maxWeight()) > 0)) return false;
        return interactionEligible(details, profile.interaction());
    }

    boolean observedTerm(Game game, String term) {
        if (game == null || game.details() == null || term == null || term.isBlank()) return false;
        String expected = normalized(term);
        return observedTerms(game).stream().map(this::normalized).anyMatch(expected::equals);
    }

    List<RecommendedGame> present(
            List<Game> selected,
            RecommendationProfile profile,
            Map<Integer, List<String>> evidenceTerms,
            boolean chinese,
            Research research) {
        return selected.stream()
                .map(game -> present(
                        game,
                        profile,
                        evidenceTerms.getOrDefault(game.ranking().bggId(), List.of()),
                        chinese,
                        research))
                .toList();
    }

    Candidate researchCandidate(Game game) {
        Details details = game.details();
        return new Candidate(
                game.ranking().bggId(),
                game.ranking().sourceName(),
                game.ranking().publicationYear(),
                game.ranking().overallRank(),
                game.ranking().averageRating(),
                details.averageWeight(),
                details.minPlayers(),
                details.maxPlayers(),
                details.minimumPlayTimeMinutes(),
                details.maximumPlayTimeMinutes(),
                bounded(details.categories(), 12),
                bounded(details.mechanics(), 12),
                bounded(details.families(), 8),
                bounded(details.designers(), 5),
                bounded(details.publishers(), 5));
    }

    private RecommendedGame present(
            Game game,
            RecommendationProfile profile,
            List<String> evidenceTerms,
            boolean chinese,
            Research research) {
        Details details = game.details();
        List<String> matches = new ArrayList<>();
        if (profile.players() != null) {
            matches.add(chinese
                    ? "BGG 资料确认支持 " + profile.players() + " 人"
                    : "BGG data confirms support for " + profile.players() + " players");
        }
        if (profile.maxMinutes() != null && profile.maxMinutes() > 0) {
            Integer minutes = details.maximumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.maximumPlayTimeMinutes();
            if (minutes != null) {
                matches.add(chinese
                        ? "BGG 标注最长约 " + minutes + " 分钟，在你的上限内"
                        : "BGG lists up to " + minutes + " minutes, within your limit");
            }
        }
        if (profile.maxWeight() != null
                && profile.maxWeight().compareTo(BigDecimal.ZERO) > 0
                && details.averageWeight() != null) {
            matches.add(chinese
                    ? "BGG 复杂度 " + oneDecimal(details.averageWeight()) + " / 5，在你的上限内"
                    : "BGG complexity " + oneDecimal(details.averageWeight()) + " / 5 is within your limit");
        }
        List<String> groundedTerms = evidenceTerms.stream()
                .filter(term -> observedTerm(game, term))
                .distinct()
                .limit(4)
                .toList();
        if (!groundedTerms.isEmpty()) {
            matches.add((chinese ? "Agent 依据的 BGG 机制/类型：" : "Agent-selected BGG mechanics/categories: ")
                    + String.join(chinese ? "、" : ", ", groundedTerms));
        }
        List<RecommendationReason> reasons = new ArrayList<>(matches.stream()
                .map(text -> new RecommendationReason(ReasonKind.BGG_FACT, text, List.of()))
                .toList());
        reasons.addAll(researchReasons(research, game.ranking().bggId()));
        return new RecommendedGame(game, matches, List.of(), reasons);
    }

    private List<RecommendationReason> researchReasons(Research research, int bggId) {
        Set<Integer> sourceIndexes = research.sources().stream()
                .map(source -> source.index())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return research.games().stream()
                .filter(game -> game.bggId() == bggId)
                .flatMap(game -> game.observations().stream())
                .filter(observation -> observation.text() != null
                        && !observation.text().isBlank()
                        && observation.text().length() <= 600
                        && !observation.sourceIndexes().isEmpty()
                        && sourceIndexes.containsAll(observation.sourceIndexes()))
                .limit(3)
                .map(observation -> new RecommendationReason(
                        ReasonKind.WEB_RESEARCH,
                        observation.text(),
                        observation.sourceIndexes()))
                .toList();
    }

    private boolean interactionEligible(Details details, InteractionPreference interaction) {
        if (interaction == null || interaction == InteractionPreference.ANY) return true;
        Set<String> mechanics = details.mechanics().stream()
                .map(this::normalized)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean cooperative = mechanics.contains("cooperative game");
        boolean team = mechanics.contains("team based game");
        return switch (interaction) {
            case COOPERATIVE -> cooperative;
            case TEAM -> team;
            case COMPETITIVE -> !cooperative && !team;
            case ANY -> true;
        };
    }

    private List<Game> diversify(List<Game> source, int maximum) {
        List<Game> remaining = new ArrayList<>(source);
        List<Game> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < maximum) {
            Game next = remaining.stream()
                    .filter(candidate -> selected.stream().allMatch(chosen -> overlap(candidate, chosen)
                            <= properties.diversityOverlapLimit().doubleValue()))
                    .findFirst()
                    .orElse(remaining.getFirst());
            selected.add(next);
            remaining.remove(next);
        }
        return List.copyOf(selected);
    }

    private double overlap(Game left, Game right) {
        Set<String> leftTerms = taxonomy(left.details());
        Set<String> rightTerms = taxonomy(right.details());
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) return 0;
        Set<String> intersection = new LinkedHashSet<>(leftTerms);
        intersection.retainAll(rightTerms);
        Set<String> union = new LinkedHashSet<>(leftTerms);
        union.addAll(rightTerms);
        return (double) intersection.size() / union.size();
    }

    private Set<String> taxonomy(Details details) {
        if (details == null) return Set.of();
        return java.util.stream.Stream.of(details.categories(), details.mechanics(), details.families())
                .flatMap(List::stream)
                .map(this::normalized)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<String> observedTerms(Game game) {
        Details details = game.details();
        return java.util.stream.Stream.of(
                        java.util.stream.Stream.of(
                                game.ranking().sourceName(), details.name(), details.officialChineseName()),
                        details.categories().stream(),
                        details.mechanics().stream(),
                        details.families().stream(),
                        details.designers().stream(),
                        details.publishers().stream())
                .flatMap(java.util.function.Function.identity())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<String> bounded(List<String> values, int maximum) {
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(maximum)
                .toList();
    }

    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
