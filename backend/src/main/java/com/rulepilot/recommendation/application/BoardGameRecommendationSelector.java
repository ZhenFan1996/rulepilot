package com.rulepilot.recommendation.application;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Choice;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Slate;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
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

    CandidatePool prepare(
            List<Game> source,
            RecommendationProfile profile,
            List<Integer> excludedBggIds,
            RetrievalPlan retrievalPlan,
            List<Integer> discoveredBggIds) {
        Set<Integer> discovered = discoveredBggIds == null ? Set.of() : Set.copyOf(discoveredBggIds);
        int candidatesEvaluated = (int) source.stream().filter(game -> game.details() != null).count();
        if (!source.isEmpty() && candidatesEvaluated == 0) {
            return new CandidatePool(SelectionStatus.NO_DETAILS, 0, List.of(), retrievalPlan, discovered);
        }
        List<Game> eligible = source.stream()
                .filter(game -> !excludedBggIds.contains(game.ranking().bggId()))
                .filter(game -> eligible(game.details(), profile))
                .filter(game -> satisfiesRequiredFeatures(game, retrievalPlan))
                .filter(game -> avoidsRejectedFeatures(game, retrievalPlan))
                .sorted(candidateComparator(profile, retrievalPlan, discovered))
                .toList();
        return new CandidatePool(
                eligible.isEmpty() ? SelectionStatus.NO_MATCH : SelectionStatus.READY,
                candidatesEvaluated,
                eligible,
                retrievalPlan,
                discovered);
    }

    List<Candidate> advisorCandidates(CandidatePool pool) {
        return diversify(pool.candidates(), properties.modelCandidateLimit()).stream()
                .map(this::candidate)
                .toList();
    }

    List<RecommendedGame> fallback(CandidatePool pool, RecommendationProfile profile, boolean chinese) {
        return diversify(pool.candidates(), properties.resultCount()).stream()
                .map(game -> factsOnly(game, profile, pool.retrievalPlan(), chinese))
                .toList();
    }

    List<RecommendedGame> fromSlate(
            CandidatePool pool,
            Slate slate,
            RecommendationProfile profile,
            boolean chinese,
            Research research) {
        Map<Integer, Game> candidates = pool.candidates().stream().collect(java.util.stream.Collectors.toMap(
                game -> game.ranking().bggId(), Function.identity()));
        List<RecommendedGame> result = new ArrayList<>();
        for (Choice choice : slate.choices()) {
            Game game = candidates.get(choice.bggId());
            if (game == null) return List.of();
            RecommendedGame facts = factsOnly(game, profile, pool.retrievalPlan(), chinese);
            List<RecommendationReason> reasons = new ArrayList<>(facts.reasons());
            choice.preferenceReasons().forEach(text -> reasons.add(new RecommendationReason(
                    ReasonKind.PREFERENCE_INFERENCE, text, List.of())));
            reasons.addAll(validatedResearchReasons(research, choice.bggId()));
            List<String> matches = new ArrayList<>(facts.matches());
            matches.addAll(choice.preferenceReasons());
            List<String> tradeoffs = new ArrayList<>(facts.tradeoffs());
            tradeoffs.addAll(choice.tradeoffs());
            result.add(new RecommendedGame(game, matches, tradeoffs, reasons));
        }
        return List.copyOf(result);
    }

    private List<RecommendationReason> validatedResearchReasons(Research research, int bggId) {
        Set<Integer> validSources = research.sources().stream()
                .map(com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return research.games().stream()
                .filter(game -> game.bggId() == bggId)
                .flatMap(game -> game.observations().stream())
                .filter(observation -> observation.text() != null
                        && !observation.text().isBlank()
                        && observation.text().length() <= 600
                        && !observation.sourceIndexes().isEmpty()
                        && validSources.containsAll(observation.sourceIndexes()))
                .limit(3)
                .map(observation -> new RecommendationReason(
                        ReasonKind.WEB_RESEARCH,
                        observation.text(),
                        observation.sourceIndexes()))
                .toList();
    }

    private Candidate candidate(Game game) {
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
                details.publishers(),
                boundedDescription(details.description()));
    }

    private String boundedDescription(String value) {
        String description = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return description.length() <= 4_000 ? description : description.substring(0, 4_000);
    }

    private Comparator<Game> candidateComparator(
            RecommendationProfile profile, RetrievalPlan retrievalPlan, Set<Integer> discoveredBggIds) {
        return Comparator.comparingInt((Game game) -> discoveredBggIds.contains(game.ranking().bggId()) ? 1 : 0)
                .reversed()
                .thenComparing(Comparator.comparingInt(
                                (Game game) -> preferredFeatureFit(game, retrievalPlan))
                        .reversed())
                .thenComparing(Comparator.comparingInt(
                                (Game game) -> playerFit(game.details(), profile.players()))
                        .reversed())
                .thenComparing(Comparator.comparingInt(
                                (Game game) -> interactionFit(game.details(), profile.interaction()))
                        .reversed())
                .thenComparing(
                        (Game game) -> game.ranking().bayesAverage(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        (Game game) -> game.ranking().overallRank(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt(
                                (Game game) -> game.ranking().usersRated())
                        .reversed())
                .thenComparingInt(game -> game.ranking().bggId());
    }

    private boolean satisfiesRequiredFeatures(Game game, RetrievalPlan retrievalPlan) {
        return retrievalPlan.features().stream()
                .filter(feature -> feature.source() == FeatureSource.BGG_METADATA)
                .filter(feature -> feature.mode() == FeatureMode.REQUIRED)
                .allMatch(feature -> matchesFeature(game, feature));
    }

    private boolean avoidsRejectedFeatures(Game game, RetrievalPlan retrievalPlan) {
        return retrievalPlan.features().stream()
                .filter(feature -> feature.source() == FeatureSource.BGG_METADATA)
                .filter(feature -> feature.mode() == FeatureMode.AVOID)
                .noneMatch(feature -> matchesFeature(game, feature));
    }

    private int preferredFeatureFit(Game game, RetrievalPlan retrievalPlan) {
        return (int) retrievalPlan.features().stream()
                .filter(feature -> feature.source() == FeatureSource.BGG_METADATA)
                .filter(feature -> feature.mode() == FeatureMode.PREFERRED)
                .filter(feature -> matchesFeature(game, feature))
                .count();
    }

    private boolean matchesFeature(Game game, FeatureConstraint feature) {
        String term = normalizedFeature(feature.term());
        if (term.isBlank() || game.details() == null) return false;
        return featureValues(game).stream().map(this::normalizedFeature).anyMatch(term::equals);
    }

    private List<String> featureValues(Game game) {
        Details details = game.details();
        return java.util.stream.Stream.of(
                        java.util.stream.Stream.of(game.ranking().sourceName(), details.name(), details.officialChineseName()),
                        details.categories().stream(),
                        details.mechanics().stream(),
                        details.families().stream(),
                        details.designers().stream(),
                        details.publishers().stream())
                .flatMap(Function.identity())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String normalizedFeature(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private int playerFit(Details game, Integer players) {
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

    private boolean eligible(Details game, RecommendationProfile profile) {
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

    private int interactionFit(Details game, InteractionPreference preference) {
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

    private List<Game> diversify(List<Game> ranked, int maximum) {
        List<Game> remaining = new ArrayList<>(ranked);
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
        Set<String> intersection = new java.util.HashSet<>(leftTerms);
        intersection.retainAll(rightTerms);
        Set<String> union = new java.util.HashSet<>(leftTerms);
        union.addAll(rightTerms);
        return (double) intersection.size() / union.size();
    }

    private Set<String> taxonomy(Details game) {
        if (game == null) return Set.of();
        return java.util.stream.Stream.of(game.categories(), game.mechanics(), game.families())
                .flatMap(List::stream)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private RecommendedGame factsOnly(
            Game game, RecommendationProfile profile, RetrievalPlan retrievalPlan, boolean chinese) {
        Details details = game.details();
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
        retrievalPlan.features().stream()
                .filter(feature -> feature.source() == FeatureSource.BGG_METADATA)
                .filter(feature -> feature.mode() != FeatureMode.AVOID)
                .filter(feature -> matchesFeature(game, feature))
                .map(feature -> chinese
                        ? "BGG 元数据命中你提到的“" + feature.basedOn() + "”"
                        : "BGG metadata matches your request: “" + feature.basedOn() + "”")
                .distinct()
                .forEach(matches::add);
        matches.add(rankSignal(game, chinese));
        List<RecommendationReason> reasons = matches.stream()
                .map(text -> new RecommendationReason(ReasonKind.BGG_FACT, text, List.of()))
                .toList();
        return new RecommendedGame(game, matches, tradeoffs, reasons);
    }

    private void interactionExplanation(
            Details game,
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

    private String rankSignal(Game game, boolean chinese) {
        Integer overallRank = game.ranking().overallRank();
        if (overallRank != null) return chinese ? "BGG 总榜第 " + overallRank + " 名" : "BGG overall rank #" + overallRank;
        return chinese ? "按 BGG Geek 评分和评分人数进入候选" : "Selected by BGG Geek rating and rating volume";
    }

    private String oneDecimal(BigDecimal value) {
        return value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    record CandidatePool(
            SelectionStatus status,
            int candidatesEvaluated,
            List<Game> candidates,
            RetrievalPlan retrievalPlan,
            Set<Integer> discoveredBggIds) {
        CandidatePool {
            candidates = List.copyOf(candidates);
            retrievalPlan = retrievalPlan == null ? RetrievalPlan.empty() : retrievalPlan;
            discoveredBggIds = discoveredBggIds == null ? Set.of() : Set.copyOf(discoveredBggIds);
        }
    }

    enum SelectionStatus {
        READY,
        NO_DETAILS,
        NO_MATCH
    }
}
