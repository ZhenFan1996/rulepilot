package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReplyPart;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The deterministic boundary for application-owned recommendation selection and evidence projection. */
final class RecommendationPublication {

    private static final int MAX_ANNOTATION_CODE_POINTS = 280;
    private static final List<String> WHY_EVIDENCE_PRIORITY = List.of(
            "reportedExperience",
            "mechanics",
            "families",
            "categories",
            "playerCount",
            "durationMinutes",
            "complexity",
            "bggType",
            "bestWith",
            "recommendedWith",
            "publisherDescription",
            "designers",
            "publishers",
            "minimumAge");
    private static final List<String> BOUNDARY_EVIDENCE_PRIORITY = List.of(
            "complexity",
            "durationMinutes",
            "playerCount",
            "minimumAge",
            "bestWith",
            "recommendedWith",
            "bggType",
            "categories",
            "mechanics",
            "families",
            "reportedExperience",
            "publisherDescription",
            "designers",
            "publishers");

    private final BoardGameRecommendationSelector selector;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions observations;
    private final RecommendationReActLoop runtime;

    RecommendationPublication(
            BoardGameRecommendationSelector selector,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions observations,
            RecommendationReActLoop runtime) {
        this.selector = selector;
        this.evidenceReview = evidenceReview;
        this.observations = observations;
        this.runtime = runtime;
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            PublicationSeed seed,
            String locale) {
        Permit permit = permit(state, seed);
        boolean chinese = runtime.chinese(locale);
        Map<Integer, com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation> readyContinuations =
                new LinkedHashMap<>(state.teachingContinuations);
        Set<String> publishedEvidenceIds = new LinkedHashSet<>();
        List<RecommendedGame> games = selector.present(
                        permit.selectedGames(),
                        state.profile,
                        permit.referenceGames(),
                        chinese,
                        state.research)
                .stream()
                .map(game -> {
                    List<RecommendationReplyPart> replyParts = replyParts(
                            game.game(),
                            permit.allowedEvidenceByGame().get(game.game().ranking().bggId()),
                            state,
                            chinese);
                    replyParts.stream()
                            .flatMap(part -> part.claim().evidence().stream())
                            .map(CandidateObservation::id)
                            .forEach(publishedEvidenceIds::add);
                    return new RecommendedGame(
                            game.game(),
                            game.matches(),
                            game.tradeoffs(),
                            game.reasons(),
                            game.claims(),
                            replyParts,
                            RecommendationContinuationProjection.card(
                                    readyContinuations.get(game.game().ranking().bggId())));
                })
                .toList();

        var continuation = RecommendationContinuationProjection.response(state, games);

        String lead = seed.playerLead().isBlank() ? safeLead(chinese) : seed.playerLead();
        List<String> responseActions = new ArrayList<>(state.actions);
        if (permit.shortfall() != null) responseActions.add("RECOMMENDATION_VERIFIED_SET_SHORTFALL");
        responseActions.add("RECOMMEND_GAMES");
        List<BoardGameRecommendationAgent.ResearchSource> sources =
                runtime.responseSources(state, games, publishedEvidenceIds);
        ConversationResponse response = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                lead,
                state.profile,
                null,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                sources,
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        responseActions,
                        state.elapsedMs()),
                games,
                state.comparison,
                permit.shortfall(),
                lead,
                continuation);

        state.finalResponseGameIds.addAll(permit.selectedGames().stream()
                .map(game -> game.ranking().bggId())
                .toList());
        state.finalResponseEvidenceIds.addAll(publishedEvidenceIds);
        state.actions.clear();
        state.actions.addAll(responseActions);
        return response;
    }

    Permit permit(RecommendationAgentState state, PublicationSeed seed) {
        Objects.requireNonNull(state, "recommendation state is required");
        Objects.requireNonNull(seed, "publication seed is required");
        if (seed.candidateUse() == CandidateUse.CONTINUE_REACT) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        Set<Integer> currentRecommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        if (!currentRecommendable.containsAll(seed.candidateBggIds())) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        int requestedCount = Math.min(seed.requestedCount(), state.maximumRecommendationResults);
        List<Integer> selectedCandidateIds = seed.candidateBggIds().stream()
                .limit(requestedCount)
                .toList();

        List<Game> selectedGames = new ArrayList<>();
        Map<Integer, Map<String, CandidateObservation>> allowedEvidence = new LinkedHashMap<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        for (Integer bggId : selectedCandidateIds) {
            if (!selectedIds.add(bggId)) throw invalid(Code.DUPLICATE_SELECTION);
            Game game = state.verified.get(bggId);
            if (game == null) throw invalid(Code.FINAL_ID_NOT_VERIFIED);
            if (state.excludedIds.contains(bggId)) throw invalid(Code.FINAL_ID_EXCLUDED);
            if (state.previouslyShownIds.contains(bggId) && !state.targetGameIds.contains(bggId)) {
                throw invalid(Code.FINAL_ID_PREVIOUSLY_SHOWN);
            }
            if (state.comparisonReferenceIds.contains(bggId)) {
                throw invalid(Code.FINAL_ID_IS_COMPARISON_REFERENCE);
            }
            if (!state.targetGameIds.contains(bggId) && !selector.eligible(game, state.profile)) {
                throw invalid(Code.FINAL_ID_FAILS_HARD_GATES);
            }
            Map<String, CandidateObservation> available = observations.narrativeObservations(game, state.research);
            if (available.isEmpty()) throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            selectedGames.add(game);
            allowedEvidence.put(
                    bggId,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(available)));
        }

        List<Integer> referenceIds = seed.referenceBggIds().stream().limit(2).toList();
        if (referenceIds.stream().anyMatch(selectedIds::contains)) throw invalid(Code.REFERENCE_ID_SELECTED);
        List<Game> referenceGames = new ArrayList<>();
        for (Integer referenceId : referenceIds) {
            Game reference = state.verified.get(referenceId);
            if (reference == null || !state.comparisonReferenceIds.contains(referenceId)) {
                throw invalid(Code.REFERENCE_ID_NOT_VERIFIED);
            }
            referenceGames.add(reference);
            allowedEvidence.put(
                    referenceId,
                    java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(observations.narrativeObservations(reference, state.research))));
        }

        RecommendationShortfall shortfall = selectedGames.size() < requestedCount
                ? new RecommendationShortfall(requestedCount, selectedGames.size())
                : null;
        return new Permit(
                requestedCount,
                selectedGames,
                referenceGames,
                shortfall,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(allowedEvidence)));
    }

    private List<RecommendationReplyPart> replyParts(
            Game game,
            Map<String, CandidateObservation> availableEvidence,
            RecommendationAgentState state,
            boolean chinese) {
        if (availableEvidence == null || availableEvidence.isEmpty()) {
            throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
        }
        List<CandidateClaim> fitClaims = selector.fitClaims(game, state.profile, chinese);
        CandidateClaim why = fitClaims.stream()
                .filter(claim -> claim.relation() == CandidateClaim.Relation.SATISFIED)
                .filter(claim -> !claim.evidence().isEmpty())
                .findFirst()
                .orElseGet(() -> observedClaim(
                        preferredObservation(availableEvidence, WHY_EVIDENCE_PRIORITY),
                        chinese,
                        AnnotationPurpose.WHY_FIT));
        CandidateClaim boundary = fitClaims.stream()
                .filter(claim -> claim.strength() == ConstraintRange.Strength.SOFT)
                .filter(claim -> claim.relation() == CandidateClaim.Relation.CONFLICT)
                .filter(claim -> !claim.evidence().isEmpty())
                .findFirst()
                .orElseGet(() -> observedClaim(
                        preferredObservation(availableEvidence, BOUNDARY_EVIDENCE_PRIORITY),
                        chinese,
                        AnnotationPurpose.SELECTION_BOUNDARY));
        return List.of(
                new RecommendationReplyPart(ReplyPartRole.WHY_FIT, why),
                new RecommendationReplyPart(ReplyPartRole.TRADEOFF, boundary));
    }

    private CandidateObservation preferredObservation(
            Map<String, CandidateObservation> available,
            List<String> priority) {
        for (String attribute : priority) {
            CandidateObservation observation = available.values().stream()
                    .filter(candidate -> attribute.equals(candidate.attribute()))
                    .findFirst()
                    .orElse(null);
            if (observation != null) return observation;
        }
        return available.values().stream()
                .findFirst()
                .orElseThrow(() -> invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED));
    }

    private CandidateClaim observedClaim(
            CandidateObservation observation,
            boolean chinese,
            AnnotationPurpose purpose) {
        String value = bounded(observation.value(), 140);
        String text = purpose == AnnotationPurpose.WHY_FIT
                ? whyText(observation, value, chinese)
                : boundaryText(observation, value, chinese);
        return new CandidateClaim(
                observation.bggId(),
                observation.attribute(),
                claimType(observation),
                null,
                CandidateClaim.Relation.OBSERVED,
                bounded(text, MAX_ANNOTATION_CODE_POINTS),
                List.of(observation));
    }

    private CandidateClaim.Type claimType(CandidateObservation observation) {
        return switch (observation.kind()) {
            case STRUCTURED_METADATA -> "publisherDescription".equals(observation.attribute())
                    ? CandidateClaim.Type.PUBLISHER_DESCRIPTION
                    : CandidateClaim.Type.STRUCTURED_FACT;
            case TAXONOMY -> CandidateClaim.Type.TAXONOMY_CLASSIFICATION;
            case ATTRIBUTED_REPORT -> CandidateClaim.Type.ATTRIBUTED_EXPERIENCE;
            case RULEBOOK_FACT -> CandidateClaim.Type.STRUCTURED_FACT;
        };
    }

    private String whyText(CandidateObservation observation, String value, boolean chinese) {
        if (chinese) {
            if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
                return "一条有来源的考虑依据是：资料提到“" + value + "”。";
            }
            if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
                return "一条已核对的考虑依据是：规则书资料写明“" + value + "”。";
            }
            if ("publisherDescription".equals(observation.attribute())) {
                return "一条可追溯的考虑依据是：出版方资料写明“" + value + "”。";
            }
            return switch (observation.attribute()) {
                case "mechanics" -> "一条已核对的入选依据是：BGG 机制标签包含“" + value + "”。";
                case "categories" -> "一条已核对的入选依据是：BGG 类别标签包含“" + value + "”。";
                case "families" -> "一条已核对的入选依据是：BGG 系列资料包含“" + value + "”。";
                case "playerCount" -> "一条已核对的考虑依据是：支持人数范围为 " + value + " 人。";
                case "durationMinutes" -> "一条已核对的考虑依据是：资料时长范围为 " + value + " 分钟。";
                case "complexity" -> "一条已核对的考虑依据是：BGG 复杂度为 " + value + "。";
                case "bggType" -> "一条已核对的考虑依据是：BGG 类型为 " + value + "。";
                case "bestWith" -> "一条已核对的考虑依据是：BGG 的 best-with 资料为 " + value + "。";
                case "recommendedWith" -> "一条已核对的考虑依据是：BGG 的推荐人数资料为 " + value + "。";
                case "minimumAge" -> "一条已核对的考虑依据是：资料标注最低年龄为 " + value + "。";
                case "designers" -> "一条已核对的考虑依据是：设计者资料包含“" + value + "”。";
                case "publishers" -> "一条已核对的考虑依据是：出版方资料包含“" + value + "”。";
                default -> "一条已核对的考虑依据是：“" + value + "”。";
            };
        }
        if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
            return "One source-backed reason to consider it is this attributed report: “" + value + ".”";
        }
        if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
            return "One verified reason to consider it is this rulebook fact: “" + value + ".”";
        }
        if ("publisherDescription".equals(observation.attribute())) {
            return "One traceable reason to consider it is this publisher description: “" + value + ".”";
        }
        return switch (observation.attribute()) {
            case "mechanics" -> "One verified reason it made the slate: BGG mechanism labels include “" + value + ".”";
            case "categories" -> "One verified reason it made the slate: BGG category labels include “" + value + ".”";
            case "families" -> "One verified reason it made the slate: BGG family data includes “" + value + ".”";
            case "playerCount" -> "One verified reason to consider it: the supported player range is " + value + ".";
            case "durationMinutes" -> "One verified reason to consider it: the listed play-time range is " + value + " minutes.";
            case "complexity" -> "One verified reason to consider it: its BGG weight is " + value + ".";
            case "bggType" -> "One verified reason to consider it: its BGG type is " + value + ".";
            case "bestWith" -> "One verified reason to consider it: BGG's best-with field says " + value + ".";
            case "recommendedWith" -> "One verified reason to consider it: BGG's recommended-with field says " + value + ".";
            case "minimumAge" -> "One verified reason to consider it: the listed minimum age is " + value + ".";
            case "designers" -> "One verified reason to consider it: designer data includes “" + value + ".”";
            case "publishers" -> "One verified reason to consider it: publisher data includes “" + value + ".”";
            default -> "One verified reason to consider it is this recorded fact: “" + value + ".”";
        };
    }

    private String boundaryText(CandidateObservation observation, String value, boolean chinese) {
        if (chinese) {
            if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
                return "选择边界：来源资料提到“" + value + "”，但这是一条归因报告，不代表每桌都会如此。";
            }
            if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
                return "选择边界：规则书资料写明“" + value + "”，它只支持这项规则事实，不延伸为桌感判断。";
            }
            if ("publisherDescription".equals(observation.attribute())) {
                return "选择边界：出版方资料写明“" + value + "”，这不是独立的玩家体验结论。";
            }
            return switch (observation.attribute()) {
                case "complexity" -> "选择边界：已核对的 BGG 复杂度为 " + value + "，但该数值不保证你这桌的实际学习感受。";
                case "durationMinutes" -> "选择边界：本轮只能确认资料时长为 " + value + " 分钟，不能据此保证实际局长。";
                case "playerCount" -> "选择边界：本轮只能确认支持 " + value + " 人，不能从这个范围推出最佳人数。";
                case "minimumAge" -> "选择边界：资料标注最低年龄为 " + value + "，这不等同于你这桌的教学难度。";
                case "bestWith" -> "选择边界：BGG 的 best-with 资料为 " + value + "，它是选择参考而不是规则限制。";
                case "recommendedWith" -> "选择边界：BGG 的推荐人数资料为 " + value + "，它是参考而不是硬性保证。";
                case "bggType" -> "选择边界：BGG 类型为 " + value + "；这个分类本身不证明具体桌感。";
                case "categories" -> "选择边界：BGG 类别标签包含“" + value + "”，标签本身不保证具体体验。";
                case "mechanics" -> "选择边界：BGG 机制标签包含“" + value + "”，标签本身不保证你期待的桌感。";
                case "families" -> "选择边界：BGG 系列资料包含“" + value + "”，系列归属本身不是体验保证。";
                case "designers" -> "选择边界：设计者资料包含“" + value + "”，这只能确认创作者身份。";
                case "publishers" -> "选择边界：出版方资料包含“" + value + "”，这只能确认出版身份。";
                default -> "选择边界：本轮能确认的只是这项资料——“" + value + "”，不能据此延伸出未证实的桌感。";
            };
        }
        if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
            return "Choice boundary: an attributed source reports “" + value + ",” but that is not a universal table result.";
        }
        if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
            return "Choice boundary: the rulebook states “" + value + ";” that supports this rule fact, not a broader claim about table feel.";
        }
        if ("publisherDescription".equals(observation.attribute())) {
            return "Choice boundary: the publisher says “" + value + ";” this is not an independent player-experience finding.";
        }
        return switch (observation.attribute()) {
            case "complexity" -> "Choice boundary: the verified BGG weight is " + value + ", but that number cannot guarantee your group's learning experience.";
            case "durationMinutes" -> "Choice boundary: the listed play-time range is " + value + " minutes; it cannot guarantee your actual session length.";
            case "playerCount" -> "Choice boundary: the verified supported range is " + value + " players; that range alone does not establish the best count.";
            case "minimumAge" -> "Choice boundary: the listed minimum age is " + value + "; it is not the same as your group's teaching difficulty.";
            case "bestWith" -> "Choice boundary: BGG's best-with field says " + value + "; treat it as guidance, not a rules limit.";
            case "recommendedWith" -> "Choice boundary: BGG's recommended-with field says " + value + "; it is guidance rather than a guarantee.";
            case "bggType" -> "Choice boundary: the verified BGG type is " + value + "; that classification alone does not prove table feel.";
            case "categories" -> "Choice boundary: BGG category labels include “" + value + ";” labels alone do not guarantee the experience.";
            case "mechanics" -> "Choice boundary: BGG mechanism labels include “" + value + ";” labels alone do not guarantee the table feel you want.";
            case "families" -> "Choice boundary: BGG family data includes “" + value + ";” family membership is not an experience guarantee.";
            case "designers" -> "Choice boundary: designer data includes “" + value + ";” that establishes creator identity only.";
            case "publishers" -> "Choice boundary: publisher data includes “" + value + ";” that establishes publisher identity only.";
            default -> "Choice boundary: the available evidence establishes only “" + value + ",” not any broader claim about table feel.";
        };
    }

    private String safeLead(boolean chinese) {
        return chinese
                ? "下面是这轮已经核对并可继续查看的候选。每张卡片都列出一条有证据的考虑依据，以及一条需要留意的选择边界。"
                : "Here is the verified slate for this turn. Each card shows one evidence-backed reason to consider it and one boundary worth checking before you choose.";
    }

    private String bounded(String value, int maximumCodePoints) {
        String text = value == null ? "" : value.strip();
        int length = text.codePointCount(0, text.length());
        if (length <= maximumCodePoints) return text;
        int end = text.offsetByCodePoints(0, Math.max(1, maximumCodePoints - 1));
        return text.substring(0, end).stripTrailing() + "…";
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    record Permit(
            int requestedCount,
            List<Game> selectedGames,
            List<Game> referenceGames,
            RecommendationShortfall shortfall,
            Map<Integer, Map<String, CandidateObservation>> allowedEvidenceByGame) {
        Permit {
            selectedGames = List.copyOf(selectedGames);
            referenceGames = List.copyOf(referenceGames);
            allowedEvidenceByGame = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(allowedEvidenceByGame));
        }
    }

    enum Code {
        PUBLICATION_SEED_INVALID,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        REFERENCE_ID_SELECTED,
        REFERENCE_ID_NOT_VERIFIED
    }

    static final class InvalidPublication extends RuntimeException {
        private final Code code;

        private InvalidPublication(Code code) {
            super(code.name());
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    private enum AnnotationPurpose {
        WHY_FIT,
        SELECTION_BOUNDARY
    }
}
