package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionSnapshot;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionTurn;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.TurnResult;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.RecommendationConversationText;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
public class BggRecommendationAgentController {

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;
    private final RecommendationConversationCoordinator conversations;

    @Autowired
    public BggRecommendationAgentController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation,
            RecommendationConversationCoordinator conversations) {
        this.agent = agent;
        this.presentation = presentation;
        this.conversations = conversations;
    }

    BggRecommendationAgentController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation) {
        this(agent, presentation, null);
    }

    @PostMapping("/api/v1/bgg/recommendation-agent")
    RecommendationConversationResponse converse(
            @RequestBody RecommendationConversationRequest request,
            @RequestParam(defaultValue = "en") String locale,
            Principal principal) {
        if (request.clientTurnId() != null && conversations != null) {
            TurnResult result = conversations.converse(
                    request.toSessionTurn(), locale, principal.getName(), ignored -> {});
            return present(result, presentation);
        }
        ConversationResponse response = agent.converse(request.toCommand(), locale, principal.getName());
        return present(response, locale, presentation);
    }

    @GetMapping("/api/v1/bgg/recommendation-agent/session")
    ResponseEntity<RecommendationSessionResponse> latest(
            Principal principal) {
        if (conversations == null) return ResponseEntity.noContent().build();
        return conversations.latest(principal.getName())
                .map(snapshot -> ResponseEntity.ok(RecommendationSessionResponse.from(snapshot, presentation)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/v1/bgg/recommendation-agent/sessions")
    ResponseEntity<RecommendationSessionResponse> startNew(Principal principal) {
        if (conversations == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(RecommendationSessionResponse.from(
                conversations.startNew(principal.getName()), presentation));
    }

    @GetMapping("/api/v1/bgg/recommendation-agent/sessions")
    List<RecommendationSessionResponse> recent(Principal principal) {
        if (conversations == null) return List.of();
        return conversations.recent(principal.getName(), 50).stream()
                .map(snapshot -> RecommendationSessionResponse.from(snapshot, presentation))
                .toList();
    }

    @GetMapping("/api/v1/bgg/recommendation-agent/sessions/{conversationId}")
    ResponseEntity<RecommendationSessionResponse> find(
            @PathVariable UUID conversationId,
            Principal principal) {
        if (conversations == null) return ResponseEntity.notFound().build();
        return conversations.find(conversationId, principal.getName())
                .map(snapshot -> ResponseEntity.ok(RecommendationSessionResponse.from(snapshot, presentation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/v1/bgg/recommendation-agent/sessions/{conversationId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID conversationId,
            Principal principal) {
        if (conversations != null) conversations.delete(conversationId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    static RecommendationConversationResponse present(
            TurnResult result,
            BggRecommendationPresentation presentation) {
        return present(
                result.response(),
                result.responseLocale(),
                presentation,
                result.conversationId(),
                result.revision(),
                result.clientTurnId(),
                result.replayed());
    }

    static RecommendationConversationResponse present(
            ConversationResponse response,
            String locale,
            BggRecommendationPresentation presentation) {
        return present(response, locale, presentation, null, null, null, false);
    }

    private static RecommendationConversationResponse present(
            ConversationResponse response,
            String locale,
            BggRecommendationPresentation presentation,
            UUID conversationId,
            Long revision,
            UUID clientTurnId,
            boolean replayed) {
        java.util.stream.Stream<Game> responseGames = java.util.stream.Stream.concat(
                response.games().stream().map(RecommendedGame::game),
                response.comparison() == null
                        ? java.util.stream.Stream.empty()
                        : response.comparison().candidates().stream()
                                .map(BoardGameRecommendationAgent.ComparisonCandidate::game));
        List<Game> presentedGames = responseGames.distinct().toList();
        List<String> categories = presentedGames.stream()
                .map(game -> game.details())
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.categories().stream())
                .distinct()
                .toList();
        List<String> mechanics = presentedGames.stream()
                .map(game -> game.details())
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.mechanics().stream())
                .distinct()
                .toList();
        LocalizedTaxonomy taxonomy = presentation.localizeTaxonomy(categories, mechanics, locale);
        return RecommendationConversationResponse.from(
                response,
                taxonomy,
                locale,
                presentation,
                conversationId,
                revision,
                clientTurnId,
                replayed);
    }

    record RecommendationConversationRequest(
            RecommendationProfileRequest profile,
            String message,
            List<Integer> excludedBggIds,
            List<DialogueMessageRequest> transcript,
            Integer focusedBggId,
            List<KnownGameRequest> knownGames,
            List<Integer> shownBggIds,
            UUID conversationId,
            Long revision,
            UUID clientTurnId) {
        RecommendationConversationRequest(RecommendationProfileRequest profile, String message) {
            this(profile, message, List.of(), List.of(), null, List.of(), List.of(), null, null, null);
        }

        RecommendationConversationRequest(
                RecommendationProfileRequest profile,
                String message,
                List<Integer> excludedBggIds) {
            this(profile, message, excludedBggIds, List.of(), null, List.of(), List.of(), null, null, null);
        }

        RecommendationConversationRequest(
                RecommendationProfileRequest profile,
                String message,
                List<Integer> excludedBggIds,
                List<DialogueMessageRequest> transcript,
                Integer focusedBggId) {
            this(profile, message, excludedBggIds, transcript, focusedBggId, List.of(), List.of(), null, null, null);
        }

        RecommendationConversationRequest(
                RecommendationProfileRequest profile,
                String message,
                List<Integer> excludedBggIds,
                List<DialogueMessageRequest> transcript,
                Integer focusedBggId,
                List<KnownGameRequest> knownGames,
                List<Integer> shownBggIds) {
            this(
                    profile,
                    message,
                    excludedBggIds,
                    transcript,
                    focusedBggId,
                    knownGames,
                    shownBggIds,
                    null,
                    null,
                    null);
        }

        SessionTurn toSessionTurn() {
            return toSessionTurn(toCommand());
        }

        SessionTurn toSessionTurn(ConversationRequest command) {
            return new SessionTurn(
                    conversationId,
                    revision == null ? 0 : revision,
                    clientTurnId,
                    command);
        }

        ConversationRequest toCommand() {
            return new ConversationRequest(
                    profile == null ? RecommendationProfile.empty() : profile.toProfile(),
                    RecommendationConversationText.currentTurn(message),
                    excludedBggIds == null ? List.of() : excludedBggIds,
                    transcript == null
                            ? List.of()
                            : transcript.stream()
                                    .map(value -> new DialogueMessage(
                                            value.role(),
                                            "assistant".equals(value.role())
                                                    ? RecommendationConversationText.assistantTranscriptTurn(value.text())
                                                    : RecommendationConversationText.playerTranscriptTurn(value.text())))
                                    .toList(),
                    focusedBggId,
                    knownGames == null
                            ? List.of()
                            : knownGames.stream()
                                    .map(value -> new KnownGame(value.bggId(), value.name(), value.originalName()))
                                    .toList(),
                    shownBggIds == null ? List.of() : shownBggIds);
        }
    }

    record DialogueMessageRequest(String role, String text) {}

    record KnownGameRequest(int bggId, String name, String originalName) {}

    record RecommendationProfileRequest(
            String type,
            String interaction,
            ConstraintRangeRequest<Integer> playerCount,
            ConstraintRangeRequest<Integer> durationMinutes,
            ConstraintRangeRequest<BigDecimal> complexity) {
        RecommendationProfile toProfile() {
            return new RecommendationProfile(
                    playerCount == null ? null : playerCount.toRange(),
                    durationMinutes == null ? null : durationMinutes.toRange(),
                    complexity == null ? null : complexity.toRange(),
                    enumValue(BggGameType.class, type, BggGameType.ALL),
                    enumValue(InteractionPreference.class, interaction, InteractionPreference.ANY));
        }
    }

    record ConstraintRangeRequest<T extends Comparable<? super T>>(
            T minimum,
            T maximum,
            String strength,
            String sourceText,
            Integer confirmedTurn) {
        ConstraintRange<T> toRange() {
            return new ConstraintRange<>(
                    minimum,
                    maximum,
                    enumValue(ConstraintRange.Strength.class, strength, ConstraintRange.Strength.HARD),
                    sourceText,
                    confirmedTurn == null ? 0 : confirmedTurn);
        }
    }

    record RecommendationConversationResponse(
            UUID conversationId,
            Long revision,
            UUID clientTurnId,
            boolean replayed,
            String responseLocale,
            String outcome,
            String assistantMessage,
            RecommendationProfileResponse profile,
            ClarificationResponse clarification,
            RecommendationShortfallResponse shortfall,
            int sourceCount,
            int candidatesEvaluated,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            String failureBoundary,
            UserModelResponse userModel,
            List<ResearchSourceResponse> researchSources,
            List<String> completedWork,
            CandidateComparisonResponse comparison,
            List<RecommendedGameResponse> games) {
        static RecommendationConversationResponse from(
                ConversationResponse response,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation,
                UUID conversationId,
                Long revision,
                UUID clientTurnId,
                boolean replayed) {
            return new RecommendationConversationResponse(
                    conversationId,
                    revision,
                    clientTurnId,
                    replayed,
                    locale,
                    response.outcome().name().toLowerCase(Locale.ROOT),
                    response.assistantMessage(),
                    RecommendationProfileResponse.from(response.profile()),
                    response.clarification() == null ? null : ClarificationResponse.from(response.clarification()),
                    response.shortfall() == null ? null : RecommendationShortfallResponse.from(response.shortfall()),
                    response.sourceCount(),
                    response.candidatesEvaluated(),
                    response.harness().modelCalls(),
                    response.harness().catalogCalls(),
                    response.harness().webResearchCalls(),
                    publicFailureBoundary(response),
                    UserModelResponse.from(response.userModel()),
                    response.researchSources().stream().map(ResearchSourceResponse::from).toList(),
                    publicCompletedWork(response.harness().actions()),
                    response.comparison() == null
                            ? null
                            : CandidateComparisonResponse.from(response.comparison(), taxonomy, locale, presentation),
                    response.games().stream()
                            .map(game -> RecommendedGameResponse.from(game, taxonomy, locale, presentation))
                            .toList());
        }

        private static String publicFailureBoundary(ConversationResponse response) {
            if (response.outcome() != BoardGameRecommendationAgent.Outcome.UNAVAILABLE) return null;
            String unavailablePrefix = "UNAVAILABLE:";
            String code = response.harness().actions().stream()
                    .filter(action -> action.startsWith(unavailablePrefix))
                    .map(action -> action.substring(unavailablePrefix.length()))
                    .reduce((ignored, latest) -> latest)
                    .orElse("");
            if ("RUN_DEADLINE_EXCEEDED".equals(code)) return "time_budget";
            if ("MODEL_NOT_CONFIGURED".equals(code)) return "service_configuration";
            if ("BUDGET_EXHAUSTED".equals(code)) return "action_budget";
            if (code.startsWith("MODEL_PROTOCOL_FAILED:")
                    || "MODEL_OUTPUT_TRUNCATED".equals(code)
                    || "EMPTY_MODEL_TURN".equals(code)
                    || "UNSTRUCTURED_EVIDENCE_REPLY".equals(code)
                    || "INVALID_ACTION_COUNT".equals(code)) {
                return "model_response";
            }
            if (response.harness().actions().stream()
                    .anyMatch(action -> action.startsWith("PUBLICATION_FAILED:"))) {
                return "publication_boundary";
            }
            return "service_failure";
        }

        private static List<String> publicCompletedWork(List<String> actions) {
            return actions.stream()
                    .map(action -> switch (action) {
                        case "RESOLVE_BGG_REFERENCE" -> "resolve_bgg_game";
                        case "SEARCH_BGG_CATALOG" -> "browse_bgg_catalog";
                        case "SEARCH_BGG_BY_NAME" -> "inspect_candidate_titles";
                        case "LOOKUP_BGG_CANDIDATES", "LOOKUP_BGG_GAME" -> "lookup_bgg_games";
                        case "DISCOVER_CANDIDATES" -> "discover_public_candidates";
                        case "RESEARCH_GAME_FIT", "RESEARCH_GAME_QUESTION" -> "research_game_fit";
                        case "COMPARE_CANDIDATES" -> "compare_candidates";
                        case "REPORT_NO_MATCH" -> "report_no_match";
                        case "RECOMMEND_GAMES" -> "recommend_games";
                        default -> null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
    }

    record RecommendationSessionResponse(
            UUID conversationId,
            long revision,
            RecommendationProfileResponse profile,
            List<DialogueMessageRequest> transcript,
            List<KnownGameRequest> knownGames,
            List<Integer> shownBggIds,
            boolean processing,
            Instant processingSince,
            RecommendationConversationResponse latestResponse) {
        static RecommendationSessionResponse from(
                SessionSnapshot snapshot,
                BggRecommendationPresentation presentation) {
            String responseLocale = snapshot.lastResponseLocale() == null ? "en" : snapshot.lastResponseLocale();
            RecommendationConversationResponse latestResponse = snapshot.lastResponse() == null
                    ? null
                    : present(
                            snapshot.lastResponse(),
                            responseLocale,
                            presentation,
                            snapshot.conversationId(),
                            snapshot.revision(),
                            snapshot.lastClientTurnId(),
                            true);
            return new RecommendationSessionResponse(
                    snapshot.conversationId(),
                    snapshot.revision(),
                    RecommendationProfileResponse.from(snapshot.state().profile()),
                    snapshot.state().transcript().stream()
                            .map(message -> new DialogueMessageRequest(message.role(), message.text()))
                            .toList(),
                    snapshot.state().knownGames().stream()
                            .map(game -> new KnownGameRequest(game.bggId(), game.name(), game.originalName()))
                            .toList(),
                    snapshot.state().shownBggIds(),
                    snapshot.activeClientTurnId() != null,
                    snapshot.activeStartedAt(),
                    latestResponse);
        }
    }

    record RecommendationProfileResponse(
            String type,
            String interaction,
            ConstraintRangeResponse<Integer> playerCount,
            ConstraintRangeResponse<Integer> durationMinutes,
            ConstraintRangeResponse<BigDecimal> complexity) {
        static RecommendationProfileResponse from(RecommendationProfile profile) {
            return new RecommendationProfileResponse(
                    profile.type().name().toLowerCase(Locale.ROOT),
                    profile.interaction().name().toLowerCase(Locale.ROOT),
                    ConstraintRangeResponse.from(profile.playerCount()),
                    ConstraintRangeResponse.from(profile.durationMinutes()),
                    ConstraintRangeResponse.from(profile.complexity()));
        }
    }

    record ConstraintRangeResponse<T>(
            T minimum,
            T maximum,
            String strength,
            String sourceText,
            int confirmedTurn) {
        static <T extends Comparable<? super T>> ConstraintRangeResponse<T> from(ConstraintRange<T> range) {
            return range == null
                    ? null
                    : new ConstraintRangeResponse<>(
                            range.minimum(),
                            range.maximum(),
                            range.strength().name().toLowerCase(Locale.ROOT),
                            range.sourceText(),
                            range.confirmedTurn());
        }
    }

    record ClarificationResponse(String field, String prompt, List<ClarificationOptionResponse> options) {
        static ClarificationResponse from(BoardGameRecommendationAgent.Clarification clarification) {
            return new ClarificationResponse(
                    clarification.field().name().toLowerCase(Locale.ROOT),
                    clarification.prompt(),
                    clarification.options().stream()
                            .map(option -> new ClarificationOptionResponse(option.value(), option.label()))
                            .toList());
        }
    }

    record ClarificationOptionResponse(String value, String label) {}

    record RecommendationShortfallResponse(int requestedCount, int availableCount) {
        static RecommendationShortfallResponse from(
                BoardGameRecommendationAgent.RecommendationShortfall shortfall) {
            return new RecommendationShortfallResponse(
                    shortfall.requestedCount(),
                    shortfall.availableCount());
        }
    }

    record UserModelResponse(String summary, List<PreferenceHypothesisResponse> hypotheses) {
        static UserModelResponse from(BoardGameRecommendationAgent.UserModelView model) {
            return new UserModelResponse(
                    model.summary(),
                    model.hypotheses().stream().map(PreferenceHypothesisResponse::from).toList());
        }
    }

    record PreferenceHypothesisResponse(
            String field,
            String value,
            String text,
            String confidence,
            String basedOn) {
        static PreferenceHypothesisResponse from(BoardGameRecommendationAgent.PreferenceHypothesisView value) {
            return new PreferenceHypothesisResponse(
                    value.field(),
                    value.value(),
                    value.text(),
                    value.confidence().toLowerCase(Locale.ROOT),
                    value.basedOn());
        }
    }

    record ResearchSourceResponse(int index, String title, String url, String domain) {
        static ResearchSourceResponse from(BoardGameRecommendationAgent.ResearchSource source) {
            return new ResearchSourceResponse(source.index(), source.title(), source.url(), source.domain());
        }
    }

    record RecommendedGameResponse(
            CatalogGameResponse game,
            List<String> matches,
            List<String> tradeoffs,
            List<RecommendationReasonResponse> reasons,
            List<CandidateFitClaimResponse> fitClaims,
            List<RecommendationReplyPartResponse> replyParts) {
        static RecommendedGameResponse from(
                RecommendedGame game,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            return new RecommendedGameResponse(
                    CatalogGameResponse.from(game, taxonomy, locale, presentation),
                    game.matches().stream().map(value -> localizeTaxonomyText(value, taxonomy)).toList(),
                    game.tradeoffs(),
                    game.reasons().stream()
                            .map(reason -> RecommendationReasonResponse.from(reason, taxonomy))
                            .toList(),
                    game.claims().stream()
                            .filter(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                            .map(CandidateFitClaimResponse::from)
                            .toList(),
                    game.replyParts().stream()
                            .map(RecommendationReplyPartResponse::from)
                            .toList());
        }
    }

    record RecommendationReplyPartResponse(
            String role,
            String claimType,
            String subject,
            String text,
            List<Integer> sourceIndexes) {
        static RecommendationReplyPartResponse from(
                BoardGameRecommendationAgent.RecommendationReplyPart part) {
            return new RecommendationReplyPartResponse(
                    part.role().name().toLowerCase(Locale.ROOT),
                    part.claim().type().name().toLowerCase(Locale.ROOT),
                    part.claim().subject(),
                    part.claim().text(),
                    part.claim().sourceIndexes());
        }
    }

    record CandidateFitClaimResponse(String subject, String strength, String relation, String text) {
        static CandidateFitClaimResponse from(CandidateClaim claim) {
            return new CandidateFitClaimResponse(
                    claim.subject(),
                    claim.strength().name().toLowerCase(Locale.ROOT),
                    claim.relation().name().toLowerCase(Locale.ROOT),
                    claim.text());
        }
    }

    record CandidateComparisonResponse(
            List<ComparisonCandidateResponse> candidates,
            List<ComparisonAxisResponse> axes) {
        static CandidateComparisonResponse from(
                BoardGameRecommendationAgent.CandidateComparison comparison,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            return new CandidateComparisonResponse(
                    comparison.candidates().stream()
                            .map(candidate -> new ComparisonCandidateResponse(
                                    CatalogGameResponse.from(candidate.game(), taxonomy, locale, presentation),
                                    candidate.fitClaims().stream()
                                            .filter(claim -> claim.type() == CandidateClaim.Type.CONSTRAINT_FIT)
                                            .map(CandidateFitClaimResponse::from)
                                            .toList()))
                            .toList(),
                    comparison.axes().stream()
                            .map(axis -> ComparisonAxisResponse.from(axis, taxonomy, locale))
                            .toList());
        }
    }

    record ComparisonCandidateResponse(
            CatalogGameResponse game,
            List<CandidateFitClaimResponse> fitClaims) {}

    record ComparisonAxisResponse(
            String subject,
            String label,
            String capability,
            List<ComparisonCellResponse> cells) {
        static ComparisonAxisResponse from(
                BoardGameRecommendationAgent.ComparisonAxis axis,
                LocalizedTaxonomy taxonomy,
                String locale) {
            boolean chinese = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh");
            return new ComparisonAxisResponse(
                    axis.subject(),
                    comparisonSubjectLabel(axis.subject(), chinese),
                    comparisonSubjectCapability(axis.subject()),
                    axis.cells().stream()
                            .map(cell -> ComparisonCellResponse.from(cell, axis.subject(), taxonomy, chinese))
                            .toList());
        }
    }

    record ComparisonCellResponse(
            int bggId,
            String status,
            String observationKind,
            String value) {
        static ComparisonCellResponse from(
                BoardGameRecommendationAgent.ComparisonCell cell,
                String subject,
                LocalizedTaxonomy taxonomy,
                boolean chinese) {
            return cell.known()
                    ? new ComparisonCellResponse(
                            cell.bggId(),
                            "observed",
                            cell.observation().kind().name().toLowerCase(Locale.ROOT),
                            comparisonValue(subject, cell.observation().value(), taxonomy, chinese))
                    : new ComparisonCellResponse(cell.bggId(), "unknown", "", "");
        }
    }

    private static String comparisonValue(
            String subject,
            String value,
            LocalizedTaxonomy taxonomy,
            boolean chinese) {
        if (value == null || value.isBlank()) return value;
        if (subject.equals("categories") || subject.equals("mechanics")) {
            return localizeTaxonomyText(value, taxonomy);
        }
        if (subject.equals("complexity")) return value + " / 5";
        if (subject.equals("minimumAge")) return chinese ? value + " 岁以上" : "Age " + value + "+";
        if (!subject.equals("playerCount") && !subject.equals("durationMinutes")) return value;
        int separator = value.indexOf("..");
        if (separator <= 0 || separator + 2 >= value.length()) return value;
        try {
            int minimum = Integer.parseInt(value.substring(0, separator));
            int maximum = Integer.parseInt(value.substring(separator + 2));
            String range = minimum == maximum ? Integer.toString(minimum) : minimum + "–" + maximum;
            if (subject.equals("playerCount")) return chinese ? range + " 人" : range + " players";
            return chinese ? range + " 分钟" : range + " min";
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static String comparisonSubjectCapability(String subject) {
        return switch (subject) {
            case "bggType", "categories", "mechanics", "families" -> "taxonomy";
            case "reportedExperience" -> "attributed_report";
            case "rulebookFact" -> "rulebook_fact";
            default -> "structured_metadata";
        };
    }

    private static String comparisonSubjectLabel(String subject, boolean chinese) {
        return switch (subject) {
            case "playerCount" -> chinese ? "支持人数" : "Player count";
            case "durationMinutes" -> chinese ? "标注时长" : "Listed duration";
            case "complexity" -> chinese ? "BGG 复杂度" : "BGG complexity";
            case "bggType" -> chinese ? "BGG 类型（仅分类）" : "BGG type (classification only)";
            case "categories" -> chinese ? "BGG 类别（仅分类）" : "BGG categories (classification only)";
            case "mechanics" -> chinese ? "BGG 机制（仅分类）" : "BGG mechanisms (classification only)";
            case "families" -> chinese ? "BGG 系列（仅分类）" : "BGG families (classification only)";
            case "minimumAge" -> chinese ? "标注最低年龄" : "Listed minimum age";
            case "bestWith" -> chinese ? "BGG 最佳人数投票" : "BGG best-with poll";
            case "recommendedWith" -> chinese ? "BGG 推荐人数投票" : "BGG recommended-with poll";
            case "designers" -> chinese ? "设计者" : "Designers";
            case "publishers" -> chinese ? "出版方" : "Publishers";
            case "reportedExperience" -> chinese ? "有来源的玩家体验" : "Sourced player experience";
            case "rulebookFact" -> chinese ? "规则书事实" : "Rulebook fact";
            default -> throw new IllegalArgumentException("unsupported comparison subject");
        };
    }

    record RecommendationReasonResponse(String kind, String text, List<Integer> sourceIndexes) {
        static RecommendationReasonResponse from(
                BoardGameRecommendationAgent.RecommendationReason reason,
                LocalizedTaxonomy taxonomy) {
            return new RecommendationReasonResponse(
                    reason.kind().name().toLowerCase(Locale.ROOT),
                    reason.kind() == BoardGameRecommendationAgent.ReasonKind.BGG_FACT
                            ? localizeTaxonomyText(reason.text(), taxonomy)
                            : reason.text(),
                    reason.sourceIndexes());
        }
    }

    record CatalogGameResponse(
            int bggId,
            String name,
            String originalName,
            boolean nameLocalized,
            Integer publicationYear,
            Integer overallRank,
            BigDecimal geekRating,
            BigDecimal averageRating,
            int usersRated,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumPlayTimeMinutes,
            Integer maximumPlayTimeMinutes,
            Integer minimumAge,
            Integer suggestedMinimumAge,
            String bestWith,
            String recommendedWith,
            Integer languageDependenceLevel,
            BigDecimal averageWeight,
            Integer weightVotes,
            List<String> categories,
            List<String> mechanics,
            List<String> families,
            List<String> designers,
            List<String> publishers,
            String bggUrl) {
        static CatalogGameResponse from(
                RecommendedGame recommendation,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            return from(recommendation.game(), taxonomy, locale, presentation);
        }

        static CatalogGameResponse from(
                Game browse,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            Details details = browse.details();
            boolean localized = details != null
                    && presentation.usesSimplifiedChinese(locale)
                    && !details.officialChineseName().isBlank();
            String sourceName = browse.ranking().sourceName();
            String displayName = localized
                    ? details.officialChineseName()
                    : presentation.usesSimplifiedChinese(locale)
                            ? presentation.normalizeSourceName(sourceName)
                            : sourceName;
            return new CatalogGameResponse(
                    browse.ranking().bggId(),
                    displayName,
                    sourceName,
                    localized,
                    browse.ranking().publicationYear(),
                    browse.ranking().overallRank(),
                    browse.ranking().bayesAverage(),
                    browse.ranking().averageRating(),
                    browse.ranking().usersRated(),
                    details == null ? "" : details.thumbnailUrl(),
                    details == null ? null : details.minPlayers(),
                    details == null ? null : details.maxPlayers(),
                    details == null ? null : details.playingTimeMinutes(),
                    details == null ? null : details.minimumPlayTimeMinutes(),
                    details == null ? null : details.maximumPlayTimeMinutes(),
                    details == null ? null : details.minimumAge(),
                    details == null ? null : details.suggestedMinimumAge(),
                    details == null ? "" : details.bestWith(),
                    details == null ? "" : details.recommendedWith(),
                    details == null ? null : details.languageDependenceLevel(),
                    details == null ? null : details.averageWeight(),
                    details == null ? null : details.weightVotes(),
                    details == null ? List.of() : translate(details.categories(), taxonomy.categories()),
                    details == null ? List.of() : translate(details.mechanics(), taxonomy.mechanics()),
                    details == null ? List.of() : details.families(),
                    details == null ? List.of() : details.designers(),
                    details == null ? List.of() : details.publishers(),
                    "https://boardgamegeek.com/boardgame/" + browse.ranking().bggId());
        }

        private static List<String> translate(List<String> values, Map<String, String> translations) {
            return values.stream().map(value -> translations.getOrDefault(value, value)).toList();
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("recommendation profile contains an unsupported value");
        }
    }

    static String localizeTaxonomyText(String text, LocalizedTaxonomy taxonomy) {
        if (text == null || text.isBlank()) return text;
        String localized = text;
        List<Map.Entry<String, String>> translations = java.util.stream.Stream.concat(
                        taxonomy.categories().entrySet().stream(), taxonomy.mechanics().entrySet().stream())
                .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                .toList();
        for (Map.Entry<String, String> translation : translations) {
            localized = localized.replace(translation.getKey(), translation.getValue());
        }
        return localized;
    }
}
