package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Deterministic selection and evidence-ownership boundary for model-written recommendation prose. */
final class RecommendationPublication {

    private static final int MAX_LEAD_CODE_POINTS = 1_200;
    private static final int MAX_ANNOTATION_CODE_POINTS = 320;
    private final BoardGameRecommendationSelector selector;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions observations;
    private final RecommendationReActLoop runtime;
    private final ObjectMapper json;

    RecommendationPublication(
            BoardGameRecommendationSelector selector,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions observations,
            RecommendationReActLoop runtime,
            ObjectMapper json) {
        this.selector = selector;
        this.evidenceReview = evidenceReview;
        this.observations = observations;
        this.runtime = runtime;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            PublicationSeed seed,
            String locale) {
        return publish(state, seed, null, locale);
    }

    ConversationResponse publish(
            RecommendationAgentState state,
            PublicationSeed seed,
            PublicationNarrative narrative,
            String locale) {
        Permit permit = permit(state, seed);
        boolean chinese = runtime.chinese(locale);
        Map<Integer, com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation> readyContinuations =
                new LinkedHashMap<>(state.teachingContinuations);
        Set<String> publishedEvidenceIds = new LinkedHashSet<>();
        if (narrative != null) publishedEvidenceIds.addAll(narrative.lead().evidenceIds());
        List<RecommendedGame> games = selector.present(
                        permit.selectedGames(),
                        state.profile,
                        permit.referenceGames(),
                        chinese,
                        state.research)
                .stream()
                .map(game -> {
                    CardNarrative cardNarrative = narrative == null
                            ? null
                            : narrative.cardsByBggId().get(game.game().ranking().bggId());
                    List<RecommendationReplyPart> replyParts =
                            cardNarrative == null ? List.of() : cardNarrative.replyParts();
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

        String lead = narrative == null || narrative.lead().text().isBlank()
                ? unavailableNarrativeLead(chinese)
                : narrative.lead().text();
        List<String> responseActions = new ArrayList<>(state.actions);
        if (narrative == null) {
            responseActions.add("RECOMMENDATION_NARRATIVE_UNAVAILABLE");
        } else if (narrative.lead().text().isBlank()
                || narrative.cardsByBggId().size() < permit.selectedGames().size()
                || narrative.rejectedNarrativeParts() > 0) {
            responseActions.add("RECOMMENDATION_NARRATIVE_PARTIAL");
        } else {
            responseActions.add("WRITE_GROUNDED_RECOMMENDATION");
        }
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
        return permit(state, seed, Set.of());
    }

    Permit permit(
            RecommendationAgentState state,
            PublicationSeed seed,
            Set<String> currentUserEvidenceIds) {
        Objects.requireNonNull(state, "recommendation state is required");
        Objects.requireNonNull(seed, "publication seed is required");
        Objects.requireNonNull(currentUserEvidenceIds, "current user evidence IDs are required");
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
        Set<String> allowedLeadEvidenceIds = new LinkedHashSet<>(currentUserEvidenceIds);
        selectedIds.stream()
                .map(allowedEvidence::get)
                .filter(Objects::nonNull)
                .flatMap(evidence -> evidence.keySet().stream())
                .forEach(allowedLeadEvidenceIds::add);
        return new Permit(
                requestedCount,
                selectedGames,
                referenceGames,
                shortfall,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(allowedEvidence)),
                java.util.Collections.unmodifiableSet(allowedLeadEvidenceIds));
    }

    PublicationNarrative validateNarrative(String value, Permit permit) {
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (JsonProcessingException failure) {
            throw invalid(Code.NARRATIVE_JSON_INVALID);
        }
        requireExactObject(root, Set.of("lead", "cards"));
        int rejected = 0;
        LeadNarrative lead;
        try {
            lead = validateLeadNarrative(
                    root.path("lead"),
                    permit.allowedLeadEvidenceIds());
        } catch (InvalidPublication failure) {
            lead = new LeadNarrative("", List.of());
            rejected++;
        }
        JsonNode cards = root.path("cards");
        if (!cards.isArray() || cards.isEmpty() || cards.size() > permit.selectedGames().size()) {
            return new PublicationNarrative(lead, Map.of(), rejected + 1);
        }
        Set<Integer> selectedIds = permit.selectedGames().stream()
                .map(game -> game.ranking().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, List<JsonNode>> nodesByBggId = new LinkedHashMap<>();
        for (JsonNode card : cards) {
            int bggId;
            try {
                bggId = positiveInt(card.path("bggId"));
            } catch (InvalidPublication failure) {
                rejected++;
                continue;
            }
            if (!selectedIds.contains(bggId)) {
                rejected++;
                continue;
            }
            nodesByBggId.computeIfAbsent(bggId, ignored -> new ArrayList<>()).add(card);
        }

        Map<Integer, CardNarrative> accepted = new LinkedHashMap<>();
        for (Integer bggId : selectedIds) {
            List<JsonNode> candidates = nodesByBggId.getOrDefault(bggId, List.of());
            if (candidates.size() != 1) {
                if (!candidates.isEmpty()) rejected += candidates.size();
                continue;
            }
            try {
                CardNarrativeValidation validation = validateCardNarrative(
                        candidates.getFirst(),
                        bggId,
                        permit.allowedEvidenceByGame().get(bggId));
                accepted.put(bggId, validation.narrative());
                rejected += validation.rejectedOptionalParts();
            } catch (InvalidPublication failure) {
                rejected++;
            }
        }
        return new PublicationNarrative(lead, accepted, rejected);
    }

    private LeadNarrative validateLeadNarrative(
            JsonNode value,
            Set<String> allowedEvidenceIds) {
        requireExactObject(value, Set.of("text", "evidenceIds"));
        String text = boundedText(
                value.path("text"),
                MAX_LEAD_CODE_POINTS,
                Code.NARRATIVE_LEAD_INVALID);
        return new LeadNarrative(
                text,
                validatedEvidenceIds(value.path("evidenceIds"), allowedEvidenceIds));
    }

    private CardNarrativeValidation validateCardNarrative(
            JsonNode card,
            int bggId,
            Map<String, CandidateObservation> allowedEvidence) {
        requireExactObject(card, Set.of("bggId", "why", "tradeoff"));
        RecommendationReplyPart why = narrativePart(
                card.path("why"),
                bggId,
                ReplyPartRole.WHY_FIT,
                allowedEvidence);
        RecommendationReplyPart tradeoff = null;
        int rejectedOptionalParts = 0;
        if (!card.path("tradeoff").isNull()) {
            try {
                tradeoff = narrativePart(
                        card.path("tradeoff"),
                        bggId,
                        ReplyPartRole.TRADEOFF,
                        allowedEvidence);
            } catch (InvalidPublication failure) {
                rejectedOptionalParts++;
            }
        }
        return new CardNarrativeValidation(
                new CardNarrative(tradeoff == null ? List.of(why) : List.of(why, tradeoff)),
                rejectedOptionalParts);
    }

    private RecommendationReplyPart narrativePart(
            JsonNode value,
            int bggId,
            ReplyPartRole role,
            Map<String, CandidateObservation> allowedEvidence) {
        requireExactObject(value, Set.of("text", "evidenceIds"));
        String text = boundedText(
                value.path("text"),
                MAX_ANNOTATION_CODE_POINTS,
                Code.NARRATIVE_TEXT_INVALID);
        List<String> evidenceIds = validatedEvidenceIds(
                value.path("evidenceIds"),
                allowedEvidence == null ? Set.of() : allowedEvidence.keySet());
        List<CandidateObservation> evidence = new ArrayList<>();
        for (String id : evidenceIds) {
            CandidateObservation observation = allowedEvidence == null
                    ? null
                    : allowedEvidence.get(id);
            if (observation == null || observation.bggId() != bggId) {
                throw invalid(Code.NARRATIVE_EVIDENCE_NOT_OWNED);
            }
            evidence.add(observation);
        }
        CandidateClaim claim = new CandidateClaim(
                bggId,
                "recommendation",
                CandidateClaim.Type.PREFERENCE_INFERENCE,
                null,
                CandidateClaim.Relation.OBSERVED,
                text,
                evidence);
        return new RecommendationReplyPart(role, claim);
    }

    private List<String> validatedEvidenceIds(
            JsonNode ids,
            Set<String> allowedEvidenceIds) {
        if (!ids.isArray() || ids.isEmpty()) {
            throw invalid(Code.NARRATIVE_EVIDENCE_INVALID);
        }
        List<String> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !seen.add(id.asText())) {
                throw invalid(Code.NARRATIVE_EVIDENCE_INVALID);
            }
            if (!allowedEvidenceIds.contains(id.asText())) {
                throw invalid(Code.NARRATIVE_EVIDENCE_NOT_OWNED);
            }
            selected.add(id.asText());
        }
        return List.copyOf(selected);
    }

    private void requireExactObject(JsonNode value, Set<String> expectedFields) {
        if (value == null || !value.isObject()) throw invalid(Code.NARRATIVE_SHAPE_INVALID);
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expectedFields)) throw invalid(Code.NARRATIVE_SHAPE_INVALID);
    }

    private int positiveInt(JsonNode value) {
        if (!value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid(Code.NARRATIVE_CANDIDATE_INVALID);
        }
        return value.intValue();
    }

    private String boundedText(JsonNode value, int maximumCodePoints, Code code) {
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(code);
        String text = value.asText().strip();
        int length = text.codePointCount(0, text.length());
        if (length > maximumCodePoints) throw invalid(code);
        return text;
    }

    private String unavailableNarrativeLead(boolean chinese) {
        return chinese
                ? "候选卡已通过资料与硬条件核对；这次自然讲解没有生成完成，你仍可以先查看卡片详情。"
                : "The candidate cards passed source and hard-constraint checks, but their natural-language notes did not finish; you can still inspect the cards.";
    }

    private InvalidPublication invalid(Code code) {
        return new InvalidPublication(code);
    }

    record PublicationNarrative(
            LeadNarrative lead,
            Map<Integer, CardNarrative> cardsByBggId,
            int rejectedNarrativeParts) {
        PublicationNarrative {
            Objects.requireNonNull(lead, "lead narrative is required");
            cardsByBggId = cardsByBggId == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cardsByBggId));
            if (rejectedNarrativeParts < 0) {
                throw new IllegalArgumentException("rejected narrative count must not be negative");
            }
        }
    }

    record LeadNarrative(String text, List<String> evidenceIds) {
        LeadNarrative {
            text = text == null ? "" : text.strip();
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    record CardNarrative(List<RecommendationReplyPart> replyParts) {
        CardNarrative {
            replyParts = replyParts == null ? List.of() : List.copyOf(replyParts);
        }
    }

    private record CardNarrativeValidation(
            CardNarrative narrative,
            int rejectedOptionalParts) {}

    record Permit(
            int requestedCount,
            List<Game> selectedGames,
            List<Game> referenceGames,
            RecommendationShortfall shortfall,
            Map<Integer, Map<String, CandidateObservation>> allowedEvidenceByGame,
            Set<String> allowedLeadEvidenceIds) {
        Permit {
            selectedGames = List.copyOf(selectedGames);
            referenceGames = List.copyOf(referenceGames);
            allowedEvidenceByGame = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(allowedEvidenceByGame));
            allowedLeadEvidenceIds = allowedLeadEvidenceIds == null
                    ? Set.of()
                    : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(allowedLeadEvidenceIds));
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
        REFERENCE_ID_NOT_VERIFIED,
        NARRATIVE_JSON_INVALID,
        NARRATIVE_SHAPE_INVALID,
        NARRATIVE_LEAD_INVALID,
        NARRATIVE_CARDS_INVALID,
        NARRATIVE_CANDIDATE_INVALID,
        NARRATIVE_TEXT_INVALID,
        NARRATIVE_EVIDENCE_INVALID,
        NARRATIVE_EVIDENCE_NOT_OWNED
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
}
