package com.rulepilot.recommendation.adapter.in.web;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
public class BggRecommendationAgentController {

    private final BoardGameRecommendationAgent agent;
    private final BggRecommendationPresentation presentation;

    public BggRecommendationAgentController(
            BoardGameRecommendationAgent agent,
            BggRecommendationPresentation presentation) {
        this.agent = agent;
        this.presentation = presentation;
    }

    @PostMapping("/api/v1/bgg/recommendation-agent")
    RecommendationConversationResponse converse(
            @RequestBody RecommendationConversationRequest request,
            @RequestParam(defaultValue = "en") String locale) {
        ConversationResponse response = agent.converse(request.toCommand(), locale);
        return present(response, locale, presentation);
    }

    static RecommendationConversationResponse present(
            ConversationResponse response,
            String locale,
            BggRecommendationPresentation presentation) {
        List<String> categories = response.games().stream()
                .map(RecommendedGame::game)
                .map(game -> game.details())
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.categories().stream())
                .distinct()
                .toList();
        List<String> mechanics = response.games().stream()
                .map(RecommendedGame::game)
                .map(game -> game.details())
                .filter(java.util.Objects::nonNull)
                .flatMap(game -> game.mechanics().stream())
                .distinct()
                .toList();
        LocalizedTaxonomy taxonomy = presentation.localizeTaxonomy(categories, mechanics, locale);
        return RecommendationConversationResponse.from(response, taxonomy, locale, presentation);
    }

    record RecommendationConversationRequest(
            RecommendationProfileRequest profile,
            String message,
            List<Integer> excludedBggIds,
            List<DialogueMessageRequest> transcript,
            Integer focusedBggId) {
        RecommendationConversationRequest(RecommendationProfileRequest profile, String message) {
            this(profile, message, List.of(), List.of(), null);
        }

        RecommendationConversationRequest(
                RecommendationProfileRequest profile,
                String message,
                List<Integer> excludedBggIds) {
            this(profile, message, excludedBggIds, List.of(), null);
        }

        ConversationRequest toCommand() {
            return new ConversationRequest(
                    profile == null ? RecommendationProfile.empty() : profile.toProfile(),
                    message,
                    excludedBggIds == null ? List.of() : excludedBggIds,
                    transcript == null
                            ? List.of()
                            : transcript.stream().map(value -> new DialogueMessage(value.role(), value.text())).toList(),
                    focusedBggId);
        }
    }

    record DialogueMessageRequest(String role, String text) {}

    record RecommendationProfileRequest(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            String type,
            String interaction) {
        RecommendationProfile toProfile() {
            return new RecommendationProfile(
                    players,
                    maxMinutes,
                    maxWeight,
                    enumValue(BggGameType.class, type, BggGameType.ALL),
                    enumValue(InteractionPreference.class, interaction, InteractionPreference.ANY));
        }
    }

    record RecommendationConversationResponse(
            String outcome,
            String mode,
            String assistantMessage,
            RecommendationProfileResponse profile,
            ClarificationResponse clarification,
            int sourceCount,
            int candidatesEvaluated,
            UserModelResponse userModel,
            List<ResearchSourceResponse> researchSources,
            HarnessResponse harness,
            List<RecommendedGameResponse> games) {
        static RecommendationConversationResponse from(
                ConversationResponse response,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            return new RecommendationConversationResponse(
                    response.outcome().name().toLowerCase(Locale.ROOT),
                    response.mode().name().toLowerCase(Locale.ROOT),
                    response.assistantMessage(),
                    RecommendationProfileResponse.from(response.profile()),
                    response.clarification() == null ? null : ClarificationResponse.from(response.clarification()),
                    response.sourceCount(),
                    response.candidatesEvaluated(),
                    UserModelResponse.from(response.userModel()),
                    response.researchSources().stream().map(ResearchSourceResponse::from).toList(),
                    HarnessResponse.from(response.harness()),
                    response.games().stream()
                            .map(game -> RecommendedGameResponse.from(game, taxonomy, locale, presentation))
                            .toList());
        }
    }

    record RecommendationProfileResponse(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            String type,
            String interaction) {
        static RecommendationProfileResponse from(RecommendationProfile profile) {
            return new RecommendationProfileResponse(
                    profile.players(),
                    profile.maxMinutes(),
                    profile.maxWeight(),
                    profile.type().name().toLowerCase(Locale.ROOT),
                    profile.interaction().name().toLowerCase(Locale.ROOT));
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

    record UserModelResponse(String summary, List<PreferenceHypothesisResponse> hypotheses) {
        static UserModelResponse from(BoardGameRecommendationAgent.UserModelView model) {
            return new UserModelResponse(
                    model.summary(),
                    model.hypotheses().stream().map(PreferenceHypothesisResponse::from).toList());
        }
    }

    record PreferenceHypothesisResponse(String text, String confidence, String basedOn) {
        static PreferenceHypothesisResponse from(BoardGameRecommendationAgent.PreferenceHypothesisView value) {
            return new PreferenceHypothesisResponse(
                    value.text(), value.confidence().toLowerCase(Locale.ROOT), value.basedOn());
        }
    }

    record ResearchSourceResponse(int index, String title, String url, String domain) {
        static ResearchSourceResponse from(BoardGameRecommendationAgent.ResearchSource source) {
            return new ResearchSourceResponse(source.index(), source.title(), source.url(), source.domain());
        }
    }

    record HarnessResponse(
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            List<String> actions) {
        static HarnessResponse from(BoardGameRecommendationAgent.HarnessTrace trace) {
            return new HarnessResponse(
                    trace.modelCalls(),
                    trace.catalogCalls(),
                    trace.webResearchCalls(),
                    trace.fallbackUsed(),
                    trace.actions());
        }
    }

    record RecommendedGameResponse(
            CatalogGameResponse game,
            List<String> matches,
            List<String> tradeoffs,
            List<RecommendationReasonResponse> reasons) {
        static RecommendedGameResponse from(
                RecommendedGame game,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            return new RecommendedGameResponse(
                    CatalogGameResponse.from(game, taxonomy, locale, presentation),
                    game.matches().stream().map(value -> localizeTaxonomyText(value, taxonomy)).toList(),
                    game.tradeoffs().stream().map(value -> localizeTaxonomyText(value, taxonomy)).toList(),
                    game.reasons().stream()
                            .map(reason -> RecommendationReasonResponse.from(reason, taxonomy))
                            .toList());
        }
    }

    record RecommendationReasonResponse(String kind, String text, List<Integer> sourceIndexes) {
        static RecommendationReasonResponse from(
                BoardGameRecommendationAgent.RecommendationReason reason,
                LocalizedTaxonomy taxonomy) {
            return new RecommendationReasonResponse(
                    reason.kind().name().toLowerCase(Locale.ROOT),
                    localizeTaxonomyText(reason.text(), taxonomy),
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
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            String bggUrl) {
        static CatalogGameResponse from(
                RecommendedGame recommendation,
                LocalizedTaxonomy taxonomy,
                String locale,
                BggRecommendationPresentation presentation) {
            var browse = recommendation.game();
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
                    details == null ? null : details.averageWeight(),
                    details == null ? List.of() : translate(details.categories(), taxonomy.categories()),
                    details == null ? List.of() : translate(details.mechanics(), taxonomy.mechanics()),
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

    private static String localizeTaxonomyText(String text, LocalizedTaxonomy taxonomy) {
        if (text == null || text.isBlank()) return text;
        String localized = text;
        List<Map.Entry<String, String>> translations = java.util.stream.Stream.concat(
                        taxonomy.categories().entrySet().stream(), taxonomy.mechanics().entrySet().stream())
                .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                .toList();
        for (Map.Entry<String, String> translation : translations) {
            localized = localized
                    .replace("“" + translation.getKey() + "”", "“" + translation.getValue() + "”")
                    .replace("\"" + translation.getKey() + "\"", "\"" + translation.getValue() + "\"");
        }
        return localized;
    }
}
