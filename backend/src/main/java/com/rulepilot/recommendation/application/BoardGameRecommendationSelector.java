package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return diversify(allowed, maximum);
    }

    boolean eligible(Game game, RecommendationProfile profile) {
        if (game == null || game.ranking() == null || game.details() == null) return false;
        return fitAssessments(game, profile, false).stream()
                .filter(FitAssessment::hardGate)
                .allMatch(assessment -> assessment.claim().relation() == CandidateClaim.Relation.SATISFIED);
    }

    List<RecommendedGame> present(
            List<Game> selected,
            RecommendationProfile profile,
            List<Game> references,
            boolean chinese,
            Research research) {
        return present(selected, profile, references, chinese, research, Map.of(), Map.of());
    }

    List<RecommendedGame> present(
            List<Game> selected,
            RecommendationProfile profile,
            List<Game> references,
            boolean chinese,
            Research research,
            Map<Integer, PreferenceLink> preferenceLinks) {
        return present(selected, profile, references, chinese, research, preferenceLinks, Map.of());
    }

    List<RecommendedGame> present(
            List<Game> selected,
            RecommendationProfile profile,
            List<Game> references,
            boolean chinese,
            Research research,
            Map<Integer, PreferenceLink> preferenceLinks,
            Map<Integer, CandidateNarrative> narratives) {
        return selected.stream()
                .map(game -> present(
                        game,
                        profile,
                        sharedTaxonomy(game, references),
                        chinese,
                        research,
                        preferenceLinks.get(game.ranking().bggId()),
                        narratives.get(game.ranking().bggId())))
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
                details.categories(),
                details.mechanics(),
                details.families(),
                details.designers(),
                details.publishers());
    }

    private RecommendedGame present(
            Game game,
            RecommendationProfile profile,
            List<String> sharedTaxonomy,
            boolean chinese,
            Research research,
            PreferenceLink preferenceLink,
            CandidateNarrative narrative) {
        Details details = game.details();
        List<CandidateClaim> fitClaims = fitClaims(game, profile, chinese);
        List<String> matches = new ArrayList<>();
        if (profile.playerCount() != null && satisfied(fitClaims, "playerCount")) {
            if (profile.playerCount().exact()) {
                matches.add(chinese
                        ? "BGG 资料确认支持 " + profile.playerCount().minimum() + " 人游玩"
                        : "BGG data confirms support for " + profile.playerCount().minimum() + " players");
            } else {
                String requested = integerRange(profile.playerCount(), chinese, " 人", " players");
                matches.add(chinese
                        ? "BGG 资料确认支持你的 " + requested + "范围"
                        : "BGG data confirms support across your " + requested + " range");
            }
        }
        if (profile.durationMinutes() != null && satisfied(fitClaims, "durationMinutes")) {
            Integer minimum = details.minimumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.minimumPlayTimeMinutes();
            Integer maximum = details.maximumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.maximumPlayTimeMinutes();
            if (minimum != null && maximum != null) {
                String advertised = minimum.equals(maximum)
                        ? Integer.toString(maximum)
                        : minimum + "–" + maximum;
                if (profile.durationMinutes().minimum() == null) {
                    matches.add(chinese
                            ? "BGG 标注最长约 " + maximum + " 分钟，在你的上限内"
                            : "BGG lists up to " + maximum + " minutes, within your limit");
                } else {
                    String requested = integerRange(profile.durationMinutes(), chinese, " 分钟", " minutes");
                    matches.add(chinese
                            ? "BGG 标注约 " + advertised + " 分钟，完整落在你的 " + requested + "范围"
                            : "BGG lists about " + advertised + " minutes, fully within your " + requested + " range");
                }
            }
        }
        if (profile.complexity() != null
                && details.averageWeight() != null
                && satisfied(fitClaims, "complexity")) {
            String requested = decimalRange(profile.complexity());
            matches.add(chinese
                    ? "BGG 复杂度 " + oneDecimal(details.averageWeight()) + " / 5，落在你的 " + requested + " 范围"
                    : "BGG complexity " + oneDecimal(details.averageWeight()) + " / 5 is within your " + requested + " range");
        }
        List<String> taxonomyLabels = taxonomyLabels(details);
        if (sharedTaxonomy.isEmpty() && !taxonomyLabels.isEmpty()) {
            matches.add((chinese ? "BGG 机制/类型标签：" : "BGG mechanism/category tags: ")
                    + String.join(chinese ? "、" : ", ", taxonomyLabels));
        }
        if (!sharedTaxonomy.isEmpty()) {
            matches.add((chinese ? "与参考游戏共有的 BGG 机制/类型：" : "BGG mechanisms/categories shared with the reference: ")
                    + String.join(chinese ? "、" : ", ", sharedTaxonomy));
        }
        List<RecommendationReason> reasons = new ArrayList<>(matches.stream()
                .map(text -> new RecommendationReason(ReasonKind.BGG_FACT, text, List.of()))
                .toList());
        if (narrative != null) {
            reasons.addFirst(new RecommendationReason(
                    ReasonKind.PREFERENCE_INFERENCE,
                    narrative.why(),
                    narrative.evidence().stream()
                            .flatMap(observation -> observation.sourceIndexes().stream())
                            .distinct()
                            .toList()));
        }
        if (preferenceLink != null) {
            reasons.add(new RecommendationReason(
                    ReasonKind.PREFERENCE_INFERENCE,
                    preferenceReason(preferenceLink, chinese),
                    List.of()));
        }
        List<RecommendationReason> researchReasons = researchReasons(research, game.ranking().bggId());
        reasons.addAll(researchReasons);
        List<String> tradeoffs = narrative != null && !narrative.tradeoff().isBlank()
                ? List.of(narrative.tradeoff())
                : researchReasons.isEmpty() && !taxonomyLabels.isEmpty()
                ? List.of(chinese
                        ? "BGG 标签只能说明机制分类，不能证明实际互动感或等待时间；在意这点时请继续点名比较。"
                        : "BGG tags describe mechanisms, not actual interaction or downtime; ask for a named comparison if that matters.")
                : List.of();
        return new RecommendedGame(game, matches, tradeoffs, reasons, fitClaims);
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
            Integer minimum = details.minimumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.minimumPlayTimeMinutes();
            Integer maximum = details.maximumPlayTimeMinutes() == null
                    ? details.playingTimeMinutes()
                    : details.maximumPlayTimeMinutes();
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

        if (profile.interaction() != null && profile.interaction() != InteractionPreference.ANY) {
            Set<String> mechanics = details.mechanics().stream()
                    .map(BoardGameRecommendationSelector::normalized)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean cooperative = mechanics.contains("cooperative game");
            boolean team = mechanics.contains("team based game");
            CandidateClaim.Relation relation = mechanics.isEmpty()
                    ? CandidateClaim.Relation.UNKNOWN
                    : switch (profile.interaction()) {
                        case COOPERATIVE -> cooperative
                                ? CandidateClaim.Relation.SATISFIED
                                : CandidateClaim.Relation.CONFLICT;
                        case TEAM -> team
                                ? CandidateClaim.Relation.SATISFIED
                                : CandidateClaim.Relation.CONFLICT;
                        case COMPETITIVE -> cooperative || team
                                ? CandidateClaim.Relation.CONFLICT
                                : CandidateClaim.Relation.SATISFIED;
                        case ANY -> CandidateClaim.Relation.UNKNOWN;
                    };
            assessments.add(fitAssessment(
                    bggId,
                    "mechanics",
                    ConstraintRange.Strength.HARD,
                    relation,
                    interactionFitText(profile.interaction(), relation, chinese),
                    observation(observations, "mechanics")));
        }
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
        Integer minimumMinutes = details.minimumPlayTimeMinutes() == null
                ? details.playingTimeMinutes()
                : details.minimumPlayTimeMinutes();
        Integer maximumMinutes = details.maximumPlayTimeMinutes() == null
                ? details.playingTimeMinutes()
                : details.maximumPlayTimeMinutes();
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
        return List.copyOf(values);
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

    private String interactionFitText(
            InteractionPreference interaction,
            CandidateClaim.Relation relation,
            boolean chinese) {
        return (chinese ? "候选 BGG 互动分类与硬条件 " : "Candidate BGG interaction classification versus hard constraint ")
                + interaction.name()
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

    private String preferenceReason(PreferenceLink link, boolean chinese) {
        String taxonomy = String.join(chinese ? "、" : ", ", link.taxonomyTerms());
        return chinese
                ? "你说“" + link.evidenceQuote() + "”；这款的 BGG 标签中有 " + taxonomy
                        + "。这是可核对的匹配线索，不能证明实际互动感或节奏。"
                : "You said “" + link.evidenceQuote() + "”; this game's BGG tags include " + taxonomy
                        + ". That is a checkable fit clue, not proof of its actual interaction or pace.";
    }

    private List<String> taxonomyLabels(Details details) {
        return java.util.stream.Stream.of(details.mechanics(), details.categories())
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
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
                        && !observation.sourceIndexes().isEmpty()
                        && sourceIndexes.containsAll(observation.sourceIndexes()))
                .map(observation -> new RecommendationReason(
                        ReasonKind.WEB_RESEARCH,
                        observation.text(),
                        observation.sourceIndexes()))
                .toList();
    }

    private boolean satisfied(List<CandidateClaim> claims, String subject) {
        return claims.stream().anyMatch(claim -> claim.subject().equals(subject)
                && claim.relation() == CandidateClaim.Relation.SATISFIED);
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
                .map(BoardGameRecommendationSelector::normalized)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<String> sharedTaxonomy(Game game, List<Game> references) {
        if (game == null || game.details() == null || references == null || references.isEmpty()) return List.of();
        Set<String> referenceTerms = references.stream()
                .filter(reference -> reference != null && reference.details() != null)
                .flatMap(reference -> taxonomy(reference.details()).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return java.util.stream.Stream.of(
                        game.details().mechanics(),
                        game.details().categories(),
                        game.details().families())
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .filter(term -> referenceTerms.contains(normalized(term)))
                .distinct()
                .toList();
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    record PreferenceLink(String evidenceQuote, List<String> taxonomyTerms) {
        PreferenceLink {
            evidenceQuote = evidenceQuote == null ? "" : evidenceQuote.strip().replaceAll("\\s+", " ");
            taxonomyTerms = taxonomyTerms == null ? List.of() : List.copyOf(taxonomyTerms);
            if (evidenceQuote.isBlank() || taxonomyTerms.isEmpty()) {
                throw new IllegalArgumentException("recommendation preference link is invalid");
            }
        }
    }

    record CandidateNarrative(
            String why,
            String tradeoff,
            List<CandidateObservation> evidence) {

        CandidateNarrative {
            if (why == null || why.isBlank() || tradeoff == null || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("candidate narrative is incomplete");
            }
            evidence = List.copyOf(evidence);
        }
    }

    private record FitAssessment(CandidateClaim claim, boolean hardGate) {}
}
