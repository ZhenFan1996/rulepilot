package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
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

/** The single deterministic publication boundary for model-written recommendation decisions and prose. */
final class RecommendationPublication {

    private static final int MAX_MESSAGE_CODE_POINTS = 1_200;
    private static final int MAX_MESSAGE_BLOCK_CODE_POINTS = 700;
    private static final int MAX_REASON_CODE_POINTS = 280;
    private static final int MAX_TRADEOFF_CODE_POINTS = 220;
    private static final List<String> RECOVERY_EVIDENCE_PRIORITY = List.of(
            "families",
            "mechanics",
            "categories",
            "playerCount",
            "durationMinutes",
            "complexity",
            "bggType",
            "designers",
            "publishers",
            "publisherDescription");

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

    Permit permit(
            JsonNode decision,
            RecommendationAgentState state,
            PublicationSeed seed) {
        Objects.requireNonNull(state, "recommendation state is required");
        Objects.requireNonNull(seed, "publication seed is required");
        if (seed.candidateUse() == CandidateUse.CONTINUE_REACT) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        requireObject(decision, Set.of("requestedCount", "selections", "referenceBggIds"));
        int requestedCount = integer(
                decision.path("requestedCount"),
                1,
                state.maximumRecommendationResults,
                Code.SELECTION_COUNT_INVALID);

        Set<Integer> currentRecommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        if (!currentRecommendable.containsAll(seed.candidateBggIds())) {
            throw invalid(Code.PUBLICATION_SEED_INVALID);
        }
        JsonNode selections = decision.path("selections");
        int expectedSelectionCount = Math.min(requestedCount, seed.candidateBggIds().size());
        if (!selections.isArray()
                || selections.size() != expectedSelectionCount
                || selections.isEmpty()
                || selections.size() > state.maximumRecommendationResults) {
            throw invalid(Code.SELECTION_COUNT_INVALID);
        }

        List<Game> selectedGames = new ArrayList<>();
        Map<Integer, Map<String, CandidateObservation>> allowedEvidence = new LinkedHashMap<>();
        Set<Integer> selectedIds = new LinkedHashSet<>();
        for (JsonNode selection : selections) {
            requireObject(selection, Set.of("bggId"));
            int bggId = integer(selection.path("bggId"), 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID);
            if (!selectedIds.add(bggId)) throw invalid(Code.DUPLICATE_SELECTION);
            if (!seed.candidateBggIds().contains(bggId)) throw invalid(Code.FINAL_ID_NOT_IN_PUBLICATION_SEED);
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
            if (available.isEmpty()) {
                throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            }
            selectedGames.add(game);
            allowedEvidence.put(bggId, available);
        }

        List<Integer> referenceIds = ids(decision.path("referenceBggIds"), 0, 2);
        if (!seed.referenceBggIds().containsAll(referenceIds)) {
            throw invalid(Code.REFERENCE_ID_NOT_IN_PUBLICATION_SEED);
        }
        if (referenceIds.stream().anyMatch(selectedIds::contains)) {
            throw invalid(Code.REFERENCE_ID_SELECTED);
        }
        List<Game> referenceGames = new ArrayList<>();
        for (Integer referenceId : referenceIds) {
            Game reference = state.verified.get(referenceId);
            if (reference == null || !state.comparisonReferenceIds.contains(referenceId)) {
                throw invalid(Code.REFERENCE_ID_NOT_VERIFIED);
            }
            referenceGames.add(reference);
            allowedEvidence.put(referenceId, observations.narrativeObservations(reference, state.research));
        }

        RecommendationShortfall shortfall = seed.candidateBggIds().size() < requestedCount
                ? new RecommendationShortfall(requestedCount, seed.candidateBggIds().size())
                : null;
        return new Permit(
                requestedCount,
                List.copyOf(selectedGames),
                List.copyOf(referenceGames),
                shortfall,
                immutableEvidence(allowedEvidence));
    }

    Session open(
            Permit permit,
            RecommendationAgentState state,
            String locale) {
        return new Session(
                Objects.requireNonNull(permit, "publication permit is required"),
                Objects.requireNonNull(state, "recommendation state is required"),
                locale);
    }

    private Map<Integer, Map<String, CandidateObservation>> immutableEvidence(
            Map<Integer, Map<String, CandidateObservation>> values) {
        LinkedHashMap<Integer, Map<String, CandidateObservation>> copy = new LinkedHashMap<>();
        values.forEach((id, evidence) -> copy.put(id, Map.copyOf(evidence)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private void requireObject(JsonNode value, Set<String> required) {
        if (value == null || !value.isObject()) throw invalid(Code.OBJECT_REQUIRED);
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid(Code.OBJECT_FIELDS_INVALID);
    }

    private int integer(JsonNode value, int minimum, int maximum, Code code) {
        if (!value.canConvertToInt()) throw invalid(code);
        int parsed = value.intValue();
        if (parsed < minimum || parsed > maximum) throw invalid(code);
        return parsed;
    }

    private List<Integer> ids(JsonNode value, int minimumItems, int maximumItems) {
        if (!value.isArray() || value.size() < minimumItems || value.size() > maximumItems) {
            throw invalid(Code.ID_LIST_INVALID);
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : value) values.add(integer(item, 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID));
        if (values.stream().distinct().count() != values.size()) throw invalid(Code.DUPLICATE_LIST_VALUE);
        return List.copyOf(values);
    }

    private List<String> strings(JsonNode value, int minimumItems, int maximumItems) {
        if (!value.isArray() || value.size() < minimumItems || value.size() > maximumItems) {
            throw invalid(Code.EVIDENCE_LIST_INVALID);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw invalid(Code.EVIDENCE_LIST_INVALID);
            String text = item.asText().strip();
            int length = text.codePointCount(0, text.length());
            if (length < 3 || length > 80) throw invalid(Code.EVIDENCE_LIST_INVALID);
            values.add(text);
        }
        if (values.stream().distinct().count() != values.size()) throw invalid(Code.DUPLICATE_LIST_VALUE);
        return List.copyOf(values);
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
            allowedEvidenceByGame = Map.copyOf(allowedEvidenceByGame);
        }
    }

    final class Session {
        private final Permit permit;
        private final RecommendationAgentState state;
        private final String locale;
        private final StringBuilder answer = new StringBuilder();
        private final List<String> answerSnapshots = new ArrayList<>();
        private final List<RecommendationReplyPart> replyParts = new ArrayList<>();
        private final Set<String> usedEvidenceIds = new LinkedHashSet<>();
        private final Set<Integer> gamesWithReasons = new LinkedHashSet<>();
        private final Set<Integer> gamesWithTradeoffs = new LinkedHashSet<>();
        private int messageBlocks;
        private int drainedSnapshotCount;
        private boolean finished;
        private boolean committed;
        private Set<Integer> completedGameIds = Set.of();
        private Set<String> completedEvidenceIds = Set.of();
        private List<String> completedActions = List.of();

        private Session(
                Permit permit,
                RecommendationAgentState state,
                String locale) {
            this.permit = permit;
            this.state = state;
            this.locale = locale;
        }

        void acceptBlock(JsonNode block) {
            if (finished) throw new IllegalStateException("recommendation publication is already finished");
            requireObject(block, Set.of("surface", "role", "bggId", "internalEvidenceIds", "text"));
            Surface surface = enumValue(Surface.class, block.path("surface"), Code.SURFACE_INVALID);
            BlockRole role = enumValue(BlockRole.class, block.path("role"), Code.ROLE_INVALID);
            Integer bggId = nullableId(block.path("bggId"));
            int availableEvidenceCount = permit.allowedEvidenceByGame().values().stream()
                    .mapToInt(Map::size)
                    .sum();
            List<String> evidenceIds = strings(
                    block.path("internalEvidenceIds"),
                    0,
                    availableEvidenceCount);
            if (surface == Surface.MESSAGE) {
                acceptMessage(role, bggId, evidenceIds, text(block.path("text"), MAX_MESSAGE_BLOCK_CODE_POINTS));
            } else {
                acceptCard(role, bggId, evidenceIds, block.path("text"));
            }
        }

        ConversationResponse finish() {
            if (finished) throw new IllegalStateException("recommendation publication is already finished");
            Set<Integer> selectedIds = permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (messageBlocks == 0 || answer.isEmpty()) throw invalid(Code.MESSAGE_REQUIRED);
            if (!gamesWithReasons.containsAll(selectedIds)) throw invalid(Code.CARD_REASON_REQUIRED);

            return complete(
                    answer.toString(),
                    replyParts,
                    usedEvidenceIds,
                    null);
        }

        ConversationResponse finishWithVerifiedCandidates(String failureCode) {
            if (finished) throw new IllegalStateException("recommendation publication is already finished");
            String code = failureCode != null && failureCode.matches("[A-Z0-9_]{3,80}")
                    ? failureCode
                    : "PUBLICATION_UNAVAILABLE";
            List<RecommendationReplyPart> recoveredReplyParts = new ArrayList<>(replyParts);
            Set<String> recoveredEvidenceIds = new LinkedHashSet<>(usedEvidenceIds);
            permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .filter(bggId -> !gamesWithReasons.contains(bggId))
                    .forEach(bggId -> {
                        RecoveryReason recovered = recoveryReason(bggId);
                        recoveredReplyParts.add(new RecommendationReplyPart(
                                recovered.role(),
                                recovered.claim()));
                        recoveredEvidenceIds.addAll(recovered.evidenceIds());
                    });

            String completeAnswer;
            if (answer.isEmpty()) {
                completeAnswer = runtime.chinese(locale)
                        ? "我已经确认这些候选对应到具体桌游，也整理了卡片所需的可追溯资料。最后一段写作没有完整结束，所以我先保留可选择的卡片，并用这些资料补上每款说明。"
                        : "I confirmed that these candidates refer to specific games and collected traceable material for their cards. The final passage did not finish, so I kept the selectable cards and used that material to fill each note.";
                addAnswerSnapshot(completeAnswer);
            } else {
                String notice = runtime.chinese(locale)
                        ? "后半段没有完整生成；下面缺失的卡片说明已用可追溯资料补齐。"
                        : "The later passage did not finish; missing card notes below were filled from traceable material.";
                completeAnswer = appendRecoveryNotice(notice);
            }
            return complete(
                    completeAnswer,
                    recoveredReplyParts,
                    recoveredEvidenceIds,
                    code);
        }

        private ConversationResponse complete(
                String completeAnswer,
                List<RecommendationReplyPart> publishedReplyParts,
                Set<String> publishedEvidenceIds,
                String recoveryCode) {
            Set<Integer> selectedIds = permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            List<RecommendedGame> games = selector.present(
                    permit.selectedGames(),
                    state.profile,
                    permit.referenceGames(),
                    runtime.chinese(locale),
                    state.research).stream()
                    .map(game -> new RecommendedGame(
                            game.game(),
                            game.matches(),
                            game.tradeoffs(),
                            game.reasons(),
                            game.claims(),
                            publishedReplyParts.stream()
                                    .filter(part -> part.claim().bggId()
                                            == game.game().ranking().bggId())
                                    .toList()))
                    .toList();
            List<String> responseActions = new ArrayList<>(state.actions);
            if (permit.shortfall() != null) responseActions.add("RECOMMENDATION_AVAILABILITY_SHORTFALL");
            if (recoveryCode != null) {
                responseActions.add("RECOMMENDATION_PUBLICATION_RECOVERED:" + recoveryCode);
            }
            responseActions.add("RECOMMEND_GAMES");
            var userModel = evidenceReview.userModelView(state, locale);
            List<BoardGameRecommendationAgent.ResearchSource> sources;
            try {
                sources = runtime.responseSources(state, games, publishedEvidenceIds);
            } catch (RuntimeException projectionFailure) {
                if (recoveryCode == null) throw projectionFailure;
                sources = List.of();
            }
            ConversationResponse response = new ConversationResponse(
                    Outcome.RECOMMENDATIONS,
                    DecisionMode.MODEL_ASSISTED,
                    completeAnswer,
                    state.profile,
                    null,
                    state.sourceCount,
                    state.verified.size(),
                    userModel,
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
                    completeAnswer);

            finished = true;
            completedGameIds = Set.copyOf(selectedIds);
            completedEvidenceIds = Set.copyOf(publishedEvidenceIds);
            completedActions = List.copyOf(responseActions);
            return response;
        }

        List<String> drainAnswerSnapshots() {
            if (drainedSnapshotCount == answerSnapshots.size()) return List.of();
            List<String> pending = List.copyOf(answerSnapshots.subList(drainedSnapshotCount, answerSnapshots.size()));
            drainedSnapshotCount = answerSnapshots.size();
            return pending;
        }

        void commit() {
            if (!finished) throw new IllegalStateException("recommendation publication is not finished");
            if (committed) throw new IllegalStateException("recommendation publication is already committed");
            if (drainedSnapshotCount != answerSnapshots.size()) {
                throw new IllegalStateException("recommendation publication still has undelivered snapshots");
            }
            committed = true;
            // The caller invokes this only after every queued player delivery succeeds. A late source,
            // presentation, timeout, or disconnected-listener failure therefore cannot commit a partial turn.
            state.finalResponseGameIds.addAll(completedGameIds);
            state.finalResponseEvidenceIds.addAll(completedEvidenceIds);
            state.actions.clear();
            state.actions.addAll(completedActions);
        }

        private void acceptMessage(
                BlockRole role,
                Integer bggId,
                List<String> evidenceIds,
                String text) {
            if (role != BlockRole.NARRATIVE) throw invalid(Code.MESSAGE_ROLE_INVALID);
            validateEvidence(bggId, evidenceIds, false);
            int separatorLength = answer.isEmpty() ? 0 : 2;
            int nextLength = answer.codePointCount(0, answer.length())
                    + separatorLength
                    + text.codePointCount(0, text.length());
            if (nextLength > MAX_MESSAGE_CODE_POINTS) throw invalid(Code.MESSAGE_TOO_LONG);
            if (!answer.isEmpty()) answer.append("\n\n");
            answer.append(text);
            messageBlocks++;
            usedEvidenceIds.addAll(evidenceIds);
            answerSnapshots.add(answer.toString());
            // A complete paragraph has passed the structured block boundary and every declared evidence id belongs
            // to the permitted candidates. The lifecycle owner drains this snapshot and delivers it outside its
            // state lock, so streaming never exposes partial JSON or lets a blocked network listener hold the lock.
        }

        private void addAnswerSnapshot(String text) {
            answer.setLength(0);
            answer.append(text);
            messageBlocks = Math.max(messageBlocks, 1);
            answerSnapshots.add(answer.toString());
        }

        private String appendRecoveryNotice(String notice) {
            int separatorLength = answer.isEmpty() ? 0 : 2;
            int nextLength = answer.codePointCount(0, answer.length())
                    + separatorLength
                    + notice.codePointCount(0, notice.length());
            if (nextLength <= MAX_MESSAGE_CODE_POINTS) {
                if (!answer.isEmpty()) answer.append("\n\n");
                answer.append(notice);
                answerSnapshots.add(answer.toString());
            }
            return answer.toString();
        }

        private RecoveryReason recoveryReason(int bggId) {
            CandidateClaim explicitFit = permit.selectedGames().stream()
                    .filter(game -> game.ranking().bggId() == bggId)
                    .findFirst()
                    .stream()
                    .flatMap(game -> selector.fitClaims(game, state.profile, runtime.chinese(locale)).stream())
                    .filter(claim -> claim.relation() == CandidateClaim.Relation.SATISFIED)
                    .filter(claim -> !claim.evidence().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (explicitFit != null) {
                return new RecoveryReason(
                        ReplyPartRole.WHY_FIT,
                        explicitFit,
                        explicitFit.evidence().stream().map(CandidateObservation::id).toList());
            }

            Map<String, CandidateObservation> available =
                    permit.allowedEvidenceByGame().getOrDefault(bggId, Map.of());
            CandidateObservation observation = RECOVERY_EVIDENCE_PRIORITY.stream()
                    .flatMap(attribute -> available.values().stream()
                            .filter(candidate -> attribute.equals(candidate.attribute()))
                            .limit(1))
                    .findFirst()
                    .orElseGet(() -> available.values().stream().findFirst().orElseThrow(
                            () -> invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED)));
            String value = bounded(observation.value(), 140);
            String text = recoveryFact(observation, value);
            CandidateClaim claim = new CandidateClaim(
                    bggId,
                    observation.attribute(),
                    neutralClaimType(observation),
                    null,
                    CandidateClaim.Relation.OBSERVED,
                    bounded(text, MAX_REASON_CODE_POINTS),
                    List.of(observation));
            return new RecoveryReason(ReplyPartRole.VERIFIED_FACT, claim, List.of(observation.id()));
        }

        private CandidateClaim.Type neutralClaimType(CandidateObservation observation) {
            return switch (observation.kind()) {
                case STRUCTURED_METADATA -> "publisherDescription".equals(observation.attribute())
                        ? CandidateClaim.Type.PUBLISHER_DESCRIPTION
                        : CandidateClaim.Type.STRUCTURED_FACT;
                case TAXONOMY -> CandidateClaim.Type.TAXONOMY_CLASSIFICATION;
                case ATTRIBUTED_REPORT -> CandidateClaim.Type.ATTRIBUTED_EXPERIENCE;
                case RULEBOOK_FACT -> CandidateClaim.Type.STRUCTURED_FACT;
            };
        }

        private String recoveryFact(CandidateObservation observation, String value) {
            if (runtime.chinese(locale)) {
                if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
                    return "来源资料提到：" + value + "。";
                }
                if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
                    return "规则书资料写明：" + value + "。";
                }
                if ("publisherDescription".equals(observation.attribute())) {
                    return "出版方资料写明：" + value + "。";
                }
                return switch (observation.attribute()) {
                    case "families" -> "已核对的系列资料包含：" + value + "。";
                    case "mechanics" -> "已核对的机制包括：" + value + "。";
                    case "categories" -> "已核对的类别包括：" + value + "。";
                    case "playerCount" -> "已核对的玩家人数为 " + value + "。";
                    case "durationMinutes" -> "已核对的游玩时长范围为 " + value + " 分钟。";
                    case "complexity" -> "已核对的 BGG 复杂度为 " + value + "。";
                    case "bggType" -> "已核对的 BGG 类型为 " + value + "。";
                    case "designers" -> "已核对的设计者资料包含：" + value + "。";
                    case "publishers" -> "已核对的出版方资料包含：" + value + "。";
                    default -> "卡片中的这项资料已经核对：" + value + "。";
                };
            }
            if (observation.kind() == CandidateObservation.Kind.ATTRIBUTED_REPORT) {
                return "An attributed source reports: " + value + ".";
            }
            if (observation.kind() == CandidateObservation.Kind.RULEBOOK_FACT) {
                return "The rulebook material states: " + value + ".";
            }
            if ("publisherDescription".equals(observation.attribute())) {
                return "The publisher material states: " + value + ".";
            }
            return switch (observation.attribute()) {
                case "families" -> "verified family data includes " + value + ".";
                case "mechanics" -> "verified mechanisms include " + value + ".";
                case "categories" -> "verified categories include " + value + ".";
                case "playerCount" -> "the verified player range is " + value + ".";
                case "durationMinutes" -> "the verified play-time range is " + value + " minutes.";
                case "complexity" -> "the verified BGG weight is " + value + ".";
                case "bggType" -> "the verified BGG type is " + value + ".";
                case "designers" -> "verified designer data includes " + value + ".";
                case "publishers" -> "verified publisher data includes " + value + ".";
                default -> "this card fact was verified: " + value + ".";
            };
        }

        private String bounded(String value, int maximumCodePoints) {
            String text = value == null ? "" : value.strip();
            int length = text.codePointCount(0, text.length());
            if (length <= maximumCodePoints) return text;
            int end = text.offsetByCodePoints(0, Math.max(1, maximumCodePoints - 1));
            return text.substring(0, end).stripTrailing() + "…";
        }

        private void acceptCard(
                BlockRole role,
                Integer bggId,
                List<String> evidenceIds,
                JsonNode textNode) {
            if (role != BlockRole.WHY_FIT && role != BlockRole.TRADEOFF) {
                throw invalid(Code.CARD_ROLE_INVALID);
            }
            if (bggId == null || permit.selectedGames().stream()
                    .map(game -> game.ranking().bggId())
                    .noneMatch(id -> Objects.equals(id, bggId))) {
                throw invalid(Code.CARD_GAME_INVALID);
            }
            if (evidenceIds.isEmpty()) throw invalid(Code.RECOMMENDATION_EVIDENCE_REQUIRED);
            List<CandidateObservation> evidence = validateEvidence(bggId, evidenceIds, true);
            String text = text(
                    textNode,
                    role == BlockRole.WHY_FIT ? MAX_REASON_CODE_POINTS : MAX_TRADEOFF_CODE_POINTS);
            if (role == BlockRole.WHY_FIT) {
                gamesWithReasons.add(bggId);
            } else if (!gamesWithTradeoffs.add(bggId)) {
                throw invalid(Code.DUPLICATE_TRADEOFF);
            }
            replyParts.add(new RecommendationReplyPart(
                    role == BlockRole.WHY_FIT ? ReplyPartRole.WHY_FIT : ReplyPartRole.TRADEOFF,
                    new CandidateClaim(
                            bggId,
                            "recommendationJudgment",
                            CandidateClaim.Type.PREFERENCE_INFERENCE,
                            null,
                            CandidateClaim.Relation.OBSERVED,
                            text,
                            evidence)));
            usedEvidenceIds.addAll(evidenceIds);
        }

        private List<CandidateObservation> validateEvidence(
                Integer bggId,
                List<String> evidenceIds,
                boolean selectedGameRequired) {
            if (bggId != null) {
                Map<String, CandidateObservation> allowed = permit.allowedEvidenceByGame().get(bggId);
                if (allowed == null || selectedGameRequired && permit.selectedGames().stream()
                        .map(game -> game.ranking().bggId())
                        .noneMatch(id -> Objects.equals(id, bggId))) {
                    throw invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED);
                }
                List<CandidateObservation> values = evidenceIds.stream().map(allowed::get).toList();
                if (values.stream().anyMatch(Objects::isNull)) {
                    throw invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED);
                }
                return values;
            }
            List<CandidateObservation> values = new ArrayList<>();
            for (String evidenceId : evidenceIds) {
                CandidateObservation found = permit.allowedEvidenceByGame().values().stream()
                        .map(evidence -> evidence.get(evidenceId))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> invalid(Code.BLOCK_EVIDENCE_NOT_GROUNDED));
                values.add(found);
            }
            return List.copyOf(values);
        }

        private Integer nullableId(JsonNode value) {
            return value.isNull()
                    ? null
                    : integer(value, 1, Integer.MAX_VALUE, Code.BGG_ID_INVALID);
        }

        private String text(JsonNode value, int maximumCodePoints) {
            if (!value.isTextual()) throw invalid(Code.TEXT_INVALID);
            String text = value.asText().strip();
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > maximumCodePoints) throw invalid(Code.TEXT_INVALID);
            return text;
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode value, Code code) {
            if (!value.isTextual()) throw invalid(code);
            try {
                return Enum.valueOf(type, value.asText());
            } catch (IllegalArgumentException exception) {
                throw invalid(code);
            }
        }

        private record RecoveryReason(
                ReplyPartRole role, CandidateClaim claim, List<String> evidenceIds) {
            private RecoveryReason {
                Objects.requireNonNull(role, "recovery reply role is required");
                evidenceIds = List.copyOf(evidenceIds);
            }
        }
    }

    enum Code {
        OBJECT_REQUIRED,
        OBJECT_FIELDS_INVALID,
        PUBLICATION_SEED_INVALID,
        SELECTION_COUNT_INVALID,
        BGG_ID_INVALID,
        DUPLICATE_SELECTION,
        FINAL_ID_NOT_IN_PUBLICATION_SEED,
        FINAL_ID_NOT_VERIFIED,
        FINAL_ID_EXCLUDED,
        FINAL_ID_PREVIOUSLY_SHOWN,
        FINAL_ID_IS_COMPARISON_REFERENCE,
        FINAL_ID_FAILS_HARD_GATES,
        RECOMMENDATION_EVIDENCE_REQUIRED,
        REFERENCE_ID_NOT_IN_PUBLICATION_SEED,
        REFERENCE_ID_SELECTED,
        REFERENCE_ID_NOT_VERIFIED,
        ID_LIST_INVALID,
        EVIDENCE_LIST_INVALID,
        DUPLICATE_LIST_VALUE,
        SURFACE_INVALID,
        ROLE_INVALID,
        MESSAGE_ROLE_INVALID,
        CARD_ROLE_INVALID,
        CARD_GAME_INVALID,
        BLOCK_EVIDENCE_NOT_GROUNDED,
        TEXT_INVALID,
        MESSAGE_TOO_LONG,
        DUPLICATE_TRADEOFF,
        MESSAGE_REQUIRED,
        CARD_REASON_REQUIRED
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

    static final class DeliveryFailure extends RuntimeException {
        DeliveryFailure(RuntimeException cause) {
            super("recommendation publication delivery failed", cause);
        }
    }

    private enum Surface {
        MESSAGE,
        CARD
    }

    private enum BlockRole {
        NARRATIVE,
        WHY_FIT,
        TRADEOFF
    }
}
