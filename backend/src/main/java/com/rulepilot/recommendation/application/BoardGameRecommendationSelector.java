package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        return allowed.stream().limit(maximum).toList();
    }

    boolean eligible(Game game, RecommendationProfile profile) {
        if (game == null || game.ranking() == null || game.details() == null) return false;
        return fitAssessments(game, profile, false).stream()
                .filter(FitAssessment::hardGate)
                .allMatch(assessment -> assessment.claim().relation() == CandidateClaim.Relation.SATISFIED);
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
                details.categories(),
                details.mechanics(),
                details.families(),
                details.designers(),
                details.publishers());
    }

    List<CandidateClaim> fitClaims(Game game, RecommendationProfile profile, boolean chinese) {
        return fitAssessments(game, profile, chinese).stream().map(FitAssessment::claim).toList();
    }

    private List<FitAssessment> fitAssessments(Game game, RecommendationProfile profile, boolean chinese) {
        if (game == null || game.ranking() == null || game.details() == null) return List.of();
        List<CandidateObservation> observations = observations(game);
        List<FitAssessment> assessments = new ArrayList<>();
        Details details = game.details();
        int bggId = game.ranking().bggId();

        if (profile.playerCount() != null) {
            CandidateClaim.Relation relation = details.minPlayers() == null || details.maxPlayers() == null
                    ? CandidateClaim.Relation.UNKNOWN
                    : (profile.playerCount().minimum() == null
                                    || details.minPlayers() <= profile.playerCount().minimum())
                                    && (profile.playerCount().maximum() == null
                                            || details.maxPlayers() >= profile.playerCount().maximum())
                            ? CandidateClaim.Relation.SATISFIED
                            : CandidateClaim.Relation.CONFLICT;
            assessments.add(fitAssessment(
                    bggId,
                    "playerCount",
                    profile.playerCount().strength(),
                    relation,
                    playerFitText(
                            details,
                            profile.playerCount(),
                            profile.playerCount().strength(),
                            relation,
                            chinese),
                    observation(observations, "playerCount")));
        }

        if (profile.durationMinutes() != null) {
            Integer playingTime = positiveDuration(details.playingTimeMinutes());
            Integer minimum = positiveDuration(details.minimumPlayTimeMinutes()) == null
                    ? playingTime
                    : positiveDuration(details.minimumPlayTimeMinutes());
            Integer maximum = positiveDuration(details.maximumPlayTimeMinutes()) == null
                    ? playingTime
                    : positiveDuration(details.maximumPlayTimeMinutes());
            CandidateClaim.Relation relation;
            if (minimum == null || maximum == null) {
                relation = CandidateClaim.Relation.UNKNOWN;
            } else if ((profile.durationMinutes().minimum() == null
                            || minimum >= profile.durationMinutes().minimum())
                    && (profile.durationMinutes().maximum() == null
                            || maximum <= profile.durationMinutes().maximum())) {
                relation = CandidateClaim.Relation.SATISFIED;
            } else if ((profile.durationMinutes().minimum() != null
                            && maximum < profile.durationMinutes().minimum())
                    || (profile.durationMinutes().maximum() != null
                            && minimum > profile.durationMinutes().maximum())) {
                relation = CandidateClaim.Relation.CONFLICT;
            } else {
                relation = CandidateClaim.Relation.UNKNOWN;
            }
            assessments.add(fitAssessment(
                    bggId,
                    "durationMinutes",
                    profile.durationMinutes().strength(),
                    relation,
                    durationFitText(
                            minimum,
                            maximum,
                            profile.durationMinutes(),
                            profile.durationMinutes().strength(),
                            relation,
                            chinese),
                    observation(observations, "durationMinutes")));
        }

        if (profile.complexity() != null) {
            CandidateClaim.Relation relation = details.averageWeight() == null
                    ? CandidateClaim.Relation.UNKNOWN
                    : profile.complexity().contains(details.averageWeight())
                            ? CandidateClaim.Relation.SATISFIED
                            : CandidateClaim.Relation.CONFLICT;
            assessments.add(fitAssessment(
                    bggId,
                    "complexity",
                    profile.complexity().strength(),
                    relation,
                    complexityFitText(
                            details.averageWeight(),
                            profile.complexity(),
                            profile.complexity().strength(),
                            relation,
                            chinese),
                    observation(observations, "complexity")));
        }

        if (profile.type() != null && profile.type() != BggGameType.ALL) {
            CandidateClaim.Relation relation = game.ranking().types().isEmpty()
                    ? CandidateClaim.Relation.UNKNOWN
                    : game.ranking().types().contains(profile.type())
                            ? CandidateClaim.Relation.SATISFIED
                            : CandidateClaim.Relation.CONFLICT;
            assessments.add(fitAssessment(
                    bggId,
                    "bggType",
                    ConstraintRange.Strength.HARD,
                    relation,
                    typeFitText(profile.type(), relation, chinese),
                    observation(observations, "bggType")));
        }

        // Interaction fit remains an Agent judgment over the candidate's supplied BGG taxonomy and descriptions.
        // Inferring competitive/cooperative/team status from a hand-maintained mechanics vocabulary made absence of
        // one label look like positive evidence for another mode and silently overrode the Agent's tool decision.
        return List.copyOf(assessments);
    }

    List<CandidateObservation> observations(Game game) {
        if (game == null || game.ranking() == null || game.details() == null) return List.of();
        int bggId = game.ranking().bggId();
        Details details = game.details();
        List<CandidateObservation> values = new ArrayList<>();
        if (details.minPlayers() != null && details.maxPlayers() != null) {
            values.add(metadata(bggId, "playerCount", details.minPlayers() + ".." + details.maxPlayers()));
        }
        Integer playingTime = positiveDuration(details.playingTimeMinutes());
        Integer minimumMinutes = positiveDuration(details.minimumPlayTimeMinutes()) == null
                ? playingTime
                : positiveDuration(details.minimumPlayTimeMinutes());
        Integer maximumMinutes = positiveDuration(details.maximumPlayTimeMinutes()) == null
                ? playingTime
                : positiveDuration(details.maximumPlayTimeMinutes());
        if (minimumMinutes != null && maximumMinutes != null) {
            values.add(metadata(bggId, "durationMinutes", minimumMinutes + ".." + maximumMinutes));
        }
        if (details.averageWeight() != null) {
            values.add(metadata(
                    bggId,
                    "complexity",
                    details.averageWeight().stripTrailingZeros().toPlainString()));
        }
        if (!game.ranking().types().isEmpty()) {
            values.add(taxonomy(
                    bggId,
                    "bggType",
                    game.ranking().types().stream()
                            .map(Enum::name)
                            .collect(java.util.stream.Collectors.joining(", "))));
        }
        addTaxonomy(values, bggId, "categories", details.categories());
        addTaxonomy(values, bggId, "mechanics", details.mechanics());
        addTaxonomy(values, bggId, "families", details.families());
        if (details.minimumAge() != null) {
            values.add(metadata(bggId, "minimumAge", details.minimumAge().toString()));
        }
        addMetadata(values, bggId, "bestWith", List.of(details.bestWith()));
        addMetadata(values, bggId, "recommendedWith", List.of(details.recommendedWith()));
        addMetadata(values, bggId, "designers", details.designers());
        addMetadata(values, bggId, "publishers", details.publishers());
        if (details.description() != null && !details.description().isBlank()) {
            values.add(metadata(bggId, "publisherDescription", details.description().strip()));
        }
        return List.copyOf(values);
    }

    private Integer positiveDuration(Integer minutes) {
        return minutes != null && minutes > 0 ? minutes : null;
    }

    private FitAssessment fitAssessment(
            int bggId,
            String subject,
            ConstraintRange.Strength strength,
            CandidateClaim.Relation relation,
            String text,
            CandidateObservation observation) {
        CandidateClaim claim = new CandidateClaim(
                bggId,
                subject,
                CandidateClaim.Type.CONSTRAINT_FIT,
                strength,
                relation,
                text,
                relation == CandidateClaim.Relation.UNKNOWN || observation == null
                        ? List.of()
                        : List.of(observation));
        return new FitAssessment(claim, strength == ConstraintRange.Strength.HARD);
    }

    private CandidateObservation observation(List<CandidateObservation> observations, String attribute) {
        return observations.stream()
                .filter(value -> value.attribute().equals(attribute))
                .findFirst()
                .orElse(null);
    }

    private CandidateObservation metadata(int bggId, String attribute, String value) {
        return new CandidateObservation(
                "B" + bggId + ":" + attribute,
                bggId,
                CandidateObservation.Kind.STRUCTURED_METADATA,
                attribute,
                value,
                List.of());
    }

    private CandidateObservation taxonomy(int bggId, String attribute, String value) {
        return new CandidateObservation(
                "B" + bggId + ":" + attribute,
                bggId,
                CandidateObservation.Kind.TAXONOMY,
                attribute,
                value,
                List.of());
    }

    private void addTaxonomy(
            List<CandidateObservation> target,
            int bggId,
            String attribute,
            List<String> source) {
        String value = source.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
        if (!value.isBlank()) target.add(taxonomy(bggId, attribute, value));
    }

    private void addMetadata(
            List<CandidateObservation> target,
            int bggId,
            String attribute,
            List<String> source) {
        String value = source.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
        if (!value.isBlank()) target.add(metadata(bggId, attribute, value));
    }

    private String playerFitText(
            Details details,
            ConstraintRange<Integer> requested,
            ConstraintRange.Strength strength,
            CandidateClaim.Relation relation,
            boolean chinese) {
        String candidate = details.minPlayers() == null || details.maxPlayers() == null
                ? chinese ? "未知" : "unknown"
                : details.minPlayers() + "–" + details.maxPlayers();
        String wanted = integerRange(requested, chinese, " 人", " players");
        String constraint = strength == ConstraintRange.Strength.HARD
                ? chinese ? "硬条件 " + wanted : "the " + wanted + " hard constraint"
                : chinese ? "偏好范围 " + wanted : "the preferred range " + wanted;
        return chinese
                ? "候选人数 " + candidate + " 人与" + constraint + relationSuffix(relation, true)
                : "Candidate player range " + candidate + " versus " + constraint + relationSuffix(relation, false);
    }

    private String durationFitText(
            Integer minimum,
            Integer maximum,
            ConstraintRange<Integer> requested,
            ConstraintRange.Strength strength,
            CandidateClaim.Relation relation,
            boolean chinese) {
        String candidate = minimum == null || maximum == null
                ? chinese ? "未知" : "unknown"
                : minimum + "–" + maximum + (chinese ? " 分钟" : " minutes");
        String wanted = integerRange(requested, chinese, " 分钟", " minutes");
        String constraint = strength == ConstraintRange.Strength.HARD
                ? chinese ? "硬条件 " + wanted : "the " + wanted + " hard constraint"
                : chinese ? "偏好范围 " + wanted : "the preferred range " + wanted;
        return chinese
                ? "候选时长 " + candidate + " 与" + constraint + relationSuffix(relation, true)
                : "Candidate duration " + candidate + " versus " + constraint + relationSuffix(relation, false);
    }

    private String complexityFitText(
            BigDecimal value,
            ConstraintRange<BigDecimal> requested,
            ConstraintRange.Strength strength,
            CandidateClaim.Relation relation,
            boolean chinese) {
        String candidate = value == null ? chinese ? "未知" : "unknown" : oneDecimal(value);
        String constraint = strength == ConstraintRange.Strength.HARD
                ? chinese ? "硬条件 " : "hard constraint "
                : chinese ? "偏好范围 " : "preferred range ";
        return (chinese ? "候选复杂度 " : "Candidate complexity ")
                + candidate
                + (chinese ? " 与" : " versus ")
                + constraint
                + decimalRange(requested)
                + relationSuffix(relation, chinese);
    }

    private String typeFitText(BggGameType type, CandidateClaim.Relation relation, boolean chinese) {
        return (chinese ? "候选 BGG 类型与硬条件 " : "Candidate BGG type versus hard constraint ")
                + type.name()
                + relationSuffix(relation, chinese);
    }

    private String relationSuffix(CandidateClaim.Relation relation, boolean chinese) {
        return switch (relation) {
            case SATISFIED -> chinese ? "：满足。" : ": satisfied.";
            case CONFLICT -> chinese ? "：冲突。" : ": conflict.";
            case UNKNOWN -> chinese ? "：当前资料不足，不能判定满足。" : ": unknown from the available facts.";
            case OBSERVED -> throw new IllegalArgumentException("fit relation cannot be observed");
        };
    }

    private static String integerRange(
            ConstraintRange<Integer> range,
            boolean chinese,
            String chineseUnit,
            String englishUnit) {
        String value = range.minimum() != null && range.maximum() != null
                ? range.minimum().equals(range.maximum())
                        ? range.minimum().toString()
                        : range.minimum() + "–" + range.maximum()
                : range.minimum() != null
                        ? (chinese ? "至少 " : "at least ") + range.minimum()
                        : (chinese ? "最多 " : "up to ") + range.maximum();
        return value + (chinese ? chineseUnit : englishUnit);
    }

    private static String decimalRange(ConstraintRange<BigDecimal> range) {
        if (range.minimum() != null && range.maximum() != null) {
            if (range.minimum().compareTo(range.maximum()) == 0) return oneDecimal(range.minimum());
            return oneDecimal(range.minimum()) + "–" + oneDecimal(range.maximum());
        }
        return range.minimum() != null
                ? "≥ " + oneDecimal(range.minimum())
                : "≤ " + oneDecimal(range.maximum());
    }

    private static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private record FitAssessment(CandidateClaim claim, boolean hardGate) {}
}
