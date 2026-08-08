package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BoardGameRecommendationAdvisor;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.DialogueAct;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Plan;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.ProfileView;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.Slate;
import com.rulepilot.catalog.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.DiscoverySignal;
import com.rulepilot.catalog.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseGame;
import com.rulepilot.catalog.application.BggRankedCatalogService.BrowseResult;
import com.rulepilot.catalog.application.BoardGamePreferenceDialogue.ResolvedTurn;
import com.rulepilot.catalog.application.BoardGameRecommendationSelector.CandidatePool;
import com.rulepilot.catalog.application.BoardGameRecommendationSelector.SelectionStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Independent recommendation-Agent harness: dialogue planning, allow-listed catalog
 * retrieval, optional bounded web research, candidate-aware composition, and fallback.
 */
@Service
@Profile("!test")
public class BoardGameRecommendationAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);

    private final BggRankedCatalogService catalog;
    private final BoardGamePreferenceDialogue dialogue;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationAdvisor advisor;
    private final BoardGameRecommendationWebResearch webResearch;
    private final BoardGameRecommendationProperties properties;

    public BoardGameRecommendationAgent(
            BggRankedCatalogService catalog,
            BoardGamePreferenceDialogue dialogue,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationAdvisor advisor,
            BoardGameRecommendationWebResearch webResearch,
            BoardGameRecommendationProperties properties) {
        this.catalog = catalog;
        this.dialogue = dialogue;
        this.selector = selector;
        this.advisor = advisor;
        this.webResearch = webResearch;
        this.properties = properties;
    }

    public ConversationResponse converse(ConversationRequest input, String requestedLocale) {
        ConversationRequest request = validate(input);
        String locale = BggMetadataLocalizationService.isSimplifiedChinese(requestedLocale) ? "zh-CN" : "en";
        List<String> actions = new ArrayList<>();
        int modelCalls = 0;
        int catalogCalls = 0;
        int researchCalls = 0;

        Optional<Plan> planned = Optional.empty();
        if (!request.transcript().isEmpty()) {
            modelCalls++;
            planned = safePlan(request, locale);
            if (planned.isPresent()) actions.add("PLAN_DIALOGUE");
        }
        ResolvedTurn turn = dialogue.resolve(
                request.profile(),
                request.message(),
                planned.map(Plan::explicitPatch).orElse(null),
                locale);
        UserModel userModel = planned.map(Plan::userModel).orElse(emptyUserModel());
        boolean plannedPreferenceSignal = planned
                .map(plan -> !plan.retrievalPlan().features().isEmpty()
                        || !plan.userModel().hypotheses().isEmpty())
                .orElse(false);

        if (planned.filter(plan -> plan.act() == DialogueAct.ASK).isPresent()
                && !turn.hasPreferenceSignal()
                && !plannedPreferenceSignal) {
            Plan plan = planned.orElseThrow();
            String message = !plan.assistantMessage().isBlank() ? plan.assistantMessage() : plan.nextQuestion();
            return response(
                    Outcome.NEEDS_CLARIFICATION,
                    DecisionMode.MODEL_ASSISTED,
                    message,
                    turn.profile(),
                    conversationalClarification(plan.nextQuestion()),
                    0,
                    0,
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, false, actions),
                    List.of());
        }
        boolean modelWantsCandidateWork = planned
                .map(plan -> plan.act() == DialogueAct.RECOMMEND || plan.act() == DialogueAct.EXPLAIN)
                .orElse(false);
        if (!turn.hasPreferenceSignal()
                && !plannedPreferenceSignal
                && request.focusedBggId() == null
                && !modelWantsCandidateWork) {
            return response(
                    Outcome.NEEDS_CLARIFICATION,
                    DecisionMode.DETERMINISTIC,
                    turn.clarification().prompt(),
                    turn.profile(),
                    turn.clarification(),
                    0,
                    0,
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, planned.isEmpty(), actions),
                    List.of());
        }

        BrowseResult source;
        List<BrowseGame> sourceGames;
        int sourceCount;
        try {
            catalogCalls++;
            actions.add("SEARCH_BGG_CATALOG");
            if (request.focusedBggId() != null) {
                sourceGames = catalog.browseIds(List.of(request.focusedBggId()));
                source = null;
                sourceCount = Optional.ofNullable(catalog.snapshot()).map(BggRankedCatalog.Snapshot::gameCount).orElse(0);
            } else {
                RetrievalPlan retrievalPlan = planned.map(Plan::retrievalPlan).orElseGet(RetrievalPlan::empty);
                source = catalog.recommendationCandidates(
                        turn.profile().type(), retrievalPlan.candidateTypes(), properties.candidatePoolSize());
                sourceGames = source.games();
                sourceCount = source.snapshot().map(BggRankedCatalog.Snapshot::gameCount).orElse(0);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Board-game recommendation catalog is unavailable");
            return unavailable(turn, locale, userModel, modelCalls, catalogCalls, researchCalls, actions);
        }

        RetrievalPlan retrievalPlan = planned.map(Plan::retrievalPlan).orElseGet(RetrievalPlan::empty);
        List<Integer> discoveredBggIds = List.of();
        Research candidateDiscoveryEvidence = Research.empty();
        CandidatePool pool = selector.prepare(
                sourceGames, turn.profile(), request.excludedBggIds(), retrievalPlan, discoveredBggIds);
        if (request.focusedBggId() == null && shouldDiscoverCandidates(retrievalPlan, pool)
                && webResearch.configured()) {
            researchCalls++;
            Optional<CandidateDiscovery> discovery = safeDiscovery(discoveryRequest(retrievalPlan, locale));
            if (discovery.isPresent()) {
                CandidateDiscovery completedDiscovery = discovery.orElseThrow();
                discoveredBggIds = completedDiscovery.candidates().stream()
                        .map(BoardGameRecommendationWebResearch.CandidateLead::bggId)
                        .distinct()
                        .limit(12)
                        .toList();
                if (!discoveredBggIds.isEmpty()) {
                    try {
                        catalogCalls++;
                        List<BrowseGame> discoveredGames = catalog.browseIds(discoveredBggIds);
                        candidateDiscoveryEvidence = discoveryEvidence(
                                completedDiscovery, discoveredGames, retrievalPlan);
                        sourceGames = mergeCandidates(discoveredGames, sourceGames, properties.candidatePoolSize());
                        actions.add("DISCOVER_CANDIDATES");
                        actions.add("LOOKUP_BGG_CANDIDATES");
                        if (!candidateDiscoveryEvidence.games().isEmpty()) actions.add("RESEARCH_GAME_FIT");
                        pool = selector.prepare(
                                sourceGames,
                                turn.profile(),
                                request.excludedBggIds(),
                                retrievalPlan,
                                discoveredBggIds);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Discovered BGG candidate lookup failed; keeping structured catalog candidates");
                    }
                }
            }
        }
        if (pool.status() == SelectionStatus.NO_DETAILS) {
            return response(
                    Outcome.UNAVAILABLE,
                    mode(planned),
                    chinese(locale)
                            ? "BGG 详细资料暂时不可用，所以我没有用不完整信息做推荐。你的偏好已经保留。"
                            : "BGG details are temporarily unavailable, so I did not recommend from incomplete facts. Your preferences are preserved.",
                    turn.profile(),
                    null,
                    sourceCount,
                    0,
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, true, actions),
                    List.of());
        }
        if (pool.status() == SelectionStatus.NO_MATCH) {
            return response(
                    Outcome.NO_MATCH,
                    mode(planned),
                    noMatchMessage(locale, !request.excludedBggIds().isEmpty()),
                    turn.profile(),
                    null,
                    sourceCount,
                    pool.candidatesEvaluated(),
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, planned.isEmpty(), actions),
                    List.of());
        }

        Research research = candidateDiscoveryEvidence;
        boolean researchUseful = request.focusedBggId() != null
                || planned.filter(this::experienceResearchJustified).isPresent();
        if (researchUseful && research.games().isEmpty() && webResearch.configured()) {
            List<BoardGameRecommendationAdvisor.Candidate> candidates = selector.advisorCandidates(pool).stream()
                    .limit(5)
                    .toList();
            researchCalls++;
            Optional<Research> researched = safeResearch(new BoardGameRecommendationWebResearch.Request(
                    candidates, locale));
            if (researched.isPresent()) {
                research = researched.get();
                actions.add("RESEARCH_GAME_FIT");
            }
        }

        CandidatePool completedPool = pool;
        boolean structuredRanking = canUseStructuredRanking(request, planned, research, researchUseful);
        Optional<Slate> slate = Optional.empty();
        if (planned.isPresent() && !structuredRanking) {
            modelCalls++;
            slate = safeCompose(new BoardGameRecommendationAdvisor.CompositionRequest(
                    request.transcript(),
                    profile(turn.profile()),
                    userModel,
                    selector.advisorCandidates(completedPool),
                    research,
                    request.focusedBggId(),
                    locale));
            if (slate.isPresent()) actions.add("COMPOSE_RECOMMENDATIONS");
        }
        if (structuredRanking) actions.add("RANK_STRUCTURED_CANDIDATES");

        Research completedResearch = research;
        List<RecommendedGame> games = slate
                .map(value -> selector.fromSlate(
                        completedPool, value, turn.profile(), chinese(locale), completedResearch))
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> selector.fallback(completedPool, turn.profile(), chinese(locale)));
        boolean fallback = slate.isEmpty() && !structuredRanking;
        String assistantMessage = slate.map(Slate::assistantMessage)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> structuredRanking
                        ? structuredSummary(locale, retrievalPlan)
                        : fallbackSummary(locale, turn.clarification()));
        String nextQuestion = slate.map(Slate::nextQuestion)
                .filter(value -> !value.isBlank())
                .orElse("");
        if (nextQuestion.isBlank() && planned.isPresent()) nextQuestion = planned.orElseThrow().nextQuestion();
        Clarification responseClarification = !nextQuestion.isBlank()
                ? conversationalClarification(nextQuestion)
                : turn.clarification();
        return response(
                Outcome.RECOMMENDATIONS,
                mode(planned),
                assistantMessage,
                turn.profile(),
                responseClarification,
                sourceCount,
                pool.candidatesEvaluated(),
                userModel,
                research.sources().stream()
                        .map(sourceItem -> new ResearchSource(
                                sourceItem.index(), sourceItem.title(), sourceItem.url(), sourceItem.domain()))
                        .toList(),
                new HarnessTrace(modelCalls, catalogCalls, researchCalls, fallback, actions),
                games);
    }

    private Optional<Plan> safePlan(ConversationRequest request, String locale) {
        try {
            return advisor.plan(new BoardGameRecommendationAdvisor.PlanningRequest(
                    request.transcript(), profile(request.profile()), request.focusedBggId(), locale));
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation dialogue planning failed; using deterministic fallback");
            return Optional.empty();
        }
    }

    private Optional<Slate> safeCompose(BoardGameRecommendationAdvisor.CompositionRequest request) {
        try {
            return advisor.compose(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation composition failed; using deterministic fallback");
            return Optional.empty();
        }
    }

    private Optional<Research> safeResearch(BoardGameRecommendationWebResearch.Request request) {
        try {
            return webResearch.research(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation web research failed; continuing with catalog and user-model evidence");
            return Optional.empty();
        }
    }

    private Optional<CandidateDiscovery> safeDiscovery(DiscoveryRequest request) {
        try {
            return webResearch.discover(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation candidate discovery failed; keeping structured catalog candidates");
            return Optional.empty();
        }
    }

    private boolean canUseStructuredRanking(
            ConversationRequest request,
            Optional<Plan> planned,
            Research research,
            boolean researchUseful) {
        if (planned.isEmpty()
                || request.focusedBggId() != null
                || !request.excludedBggIds().isEmpty()
                || !research.games().isEmpty()) return false;
        RetrievalPlan retrievalPlan = planned.orElseThrow().retrievalPlan();
        boolean requiredMetadata = retrievalPlan.features().stream().anyMatch(feature ->
                feature.source() == BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA
                        && feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED);
        boolean experienceSignal = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
        return requiredMetadata && !experienceSignal && !researchUseful;
    }

    private boolean experienceResearchJustified(Plan plan) {
        return plan.researchRequested() && plan.retrievalPlan().features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
    }

    private boolean shouldDiscoverCandidates(RetrievalPlan retrievalPlan, CandidatePool pool) {
        if (!retrievalPlan.candidateDiscoveryRequested() || retrievalPlan.features().isEmpty()) return false;
        boolean experienceDriven = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
        return experienceDriven
                || pool.status() != SelectionStatus.READY
                || pool.candidates().size() < Math.min(properties.modelCandidateLimit(), properties.resultCount() * 2);
    }

    private DiscoveryRequest discoveryRequest(RetrievalPlan retrievalPlan, String locale) {
        List<DiscoverySignal> signals = retrievalPlan.features().stream()
                .map(feature -> new DiscoverySignal(feature.term(), feature.mode(), feature.source()))
                .toList();
        return new DiscoveryRequest(signals, retrievalPlan.candidateTypes(), locale);
    }

    private List<BrowseGame> mergeCandidates(
            List<BrowseGame> discovered, List<BrowseGame> structured, int maximum) {
        java.util.LinkedHashMap<Integer, BrowseGame> merged = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(discovered.stream(), structured.stream())
                .forEach(game -> merged.putIfAbsent(game.ranked().bggId(), game));
        return merged.values().stream().limit(maximum).toList();
    }

    private Research discoveryEvidence(
            CandidateDiscovery discovery, List<BrowseGame> verifiedGames, RetrievalPlan retrievalPlan) {
        boolean experienceDriven = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
        if (!experienceDriven) return Research.empty();
        java.util.Set<Integer> verifiedIds = verifiedGames.stream()
                .map(game -> game.ranked().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<BoardGameRecommendationWebResearch.GameResearch> games = discovery.candidates().stream()
                .filter(lead -> verifiedIds.contains(lead.bggId()))
                .filter(lead -> !lead.fitObservation().isBlank() && !lead.sourceIndexes().isEmpty())
                .map(lead -> new BoardGameRecommendationWebResearch.GameResearch(
                        lead.bggId(),
                        List.of(new BoardGameRecommendationWebResearch.Observation(
                                lead.fitObservation(), lead.sourceIndexes()))))
                .toList();
        return games.isEmpty() ? Research.empty() : new Research(games, discovery.sources());
    }

    private ConversationRequest validate(ConversationRequest input) {
        if (input == null) throw new IllegalArgumentException("recommendation conversation request is required");
        String message = normalized(input.message(), 500, true);
        List<Integer> excluded = input.excludedBggIds() == null
                ? List.of()
                : input.excludedBggIds().stream().filter(Objects::nonNull).distinct().toList();
        if (excluded.size() > 60 || excluded.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("excludedBggIds must contain at most sixty positive ids");
        }
        if (input.focusedBggId() != null && input.focusedBggId() <= 0) {
            throw new IllegalArgumentException("focusedBggId must be positive");
        }
        List<DialogueMessage> transcript = input.transcript() == null
                ? new ArrayList<>()
                : input.transcript().stream().map(this::validatedMessage).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!message.isBlank() && (transcript.isEmpty()
                || !"user".equals(transcript.getLast().role())
                || !message.equals(transcript.getLast().text()))) {
            transcript.add(new DialogueMessage("user", message));
        }
        if (transcript.size() > 24) transcript = new ArrayList<>(transcript.subList(transcript.size() - 24, transcript.size()));
        return new ConversationRequest(
                input.profile() == null ? RecommendationProfile.empty() : input.profile(),
                message,
                excluded,
                List.copyOf(transcript),
                input.focusedBggId());
    }

    private DialogueMessage validatedMessage(DialogueMessage message) {
        if (message == null || !("user".equals(message.role()) || "assistant".equals(message.role()))) {
            throw new IllegalArgumentException("recommendation transcript role is invalid");
        }
        return new DialogueMessage(message.role(), normalized(message.text(), 500, false));
    }

    private String normalized(String value, int maximum, boolean allowBlank) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if ((!allowBlank && checked.isBlank()) || checked.length() > maximum) {
            throw new IllegalArgumentException("recommendation conversation text is invalid");
        }
        return checked;
    }

    private ConversationResponse unavailable(
            ResolvedTurn turn,
            String locale,
            UserModel userModel,
            int modelCalls,
            int catalogCalls,
            int researchCalls,
            List<String> actions) {
        return response(
                Outcome.UNAVAILABLE,
                DecisionMode.DETERMINISTIC,
                chinese(locale)
                        ? "全量目录暂时没有回应。你的偏好已经保留，可以稍后重试。"
                        : "The full catalog is temporarily unavailable. Your preferences are preserved for a retry.",
                turn.profile(),
                null,
                0,
                0,
                userModel,
                List.of(),
                new HarnessTrace(modelCalls, catalogCalls, researchCalls, true, actions),
                List.of());
    }

    private ConversationResponse response(
            Outcome outcome,
            DecisionMode mode,
            String message,
            RecommendationProfile profile,
            Clarification clarification,
            int sourceCount,
            int candidatesEvaluated,
            UserModel userModel,
            List<ResearchSource> sources,
            HarnessTrace trace,
            List<RecommendedGame> games) {
        return new ConversationResponse(
                outcome,
                mode,
                message,
                profile,
                clarification,
                sourceCount,
                candidatesEvaluated,
                new UserModelView(
                        userModel.summary(),
                        userModel.hypotheses().stream()
                                .map(value -> new PreferenceHypothesisView(
                                        value.text(), value.confidence().name(), value.basedOn()))
                                .toList()),
                sources,
                trace,
                games);
    }

    private ProfileView profile(RecommendationProfile profile) {
        return new ProfileView(
                profile.players(), profile.maxMinutes(), profile.maxWeight(), profile.type(), profile.interaction());
    }

    private UserModel emptyUserModel() {
        return new UserModel("", List.of());
    }

    private Clarification conversationalClarification(String question) {
        return question == null || question.isBlank()
                ? null
                : new Clarification(PreferenceField.CONVERSATION, question, List.of());
    }

    private String fallbackSummary(String locale, Clarification clarification) {
        String result = chinese(locale)
                ? "先给你几款方向不同的候选。推荐 Agent 暂时没有完成个性化重排，下面是基于 BGG 条件与排名的稳妥结果。"
                : "Here are several different directions. The recommendation Agent did not complete personalized reranking, so these are safe BGG constraint-and-rank results.";
        return clarification == null ? result : result + " " + clarification.prompt();
    }

    private String structuredSummary(String locale, RetrievalPlan retrievalPlan) {
        String evidence = retrievalPlan.features().stream()
                .filter(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA)
                .filter(feature -> feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED)
                .map(BoardGameRecommendationAdvisor.FeatureConstraint::basedOn)
                .findFirst()
                .orElse("");
        if (chinese(locale)) {
            return evidence.isBlank()
                    ? "我先按你明确的条件从 BGG 资料中筛出几款，再根据你的反馈继续调整。"
                    : "我先用 BGG 元数据严格筛选了“" + evidence + "”，下面这些都实际命中这个条件；你可以继续告诉我哪里不合适。";
        }
        return evidence.isBlank()
                ? "I filtered BGG metadata by your explicit constraints; tell me what to adjust next."
                : "I strictly filtered BGG metadata for “" + evidence
                        + "”; every result matches that condition, and you can steer the next round.";
    }

    private DecisionMode mode(Optional<Plan> planned) {
        return planned.isPresent() ? DecisionMode.MODEL_ASSISTED : DecisionMode.DETERMINISTIC;
    }

    private String noMatchMessage(String locale, boolean excludedPrevious) {
        if (chinese(locale)) {
            return excludedPrevious
                    ? "当前候选已经看完了。告诉我上一批哪里不对，我会更新对你的理解后换一个方向。"
                    : "这批候选里没有同时满足这些硬条件的桌游。可以放宽时长、复杂度或人数，也可以告诉我哪个条件最重要。";
        }
        return excludedPrevious
                ? "You have seen this pool. Tell me what missed the mark and I will update my understanding before changing direction."
                : "No candidate satisfies every hard constraint. Relax time, complexity, or player count, or tell me which matters most.";
    }

    private boolean chinese(String locale) {
        return "zh-CN".equals(locale);
    }

    public record ConversationRequest(
            RecommendationProfile profile,
            String message,
            List<Integer> excludedBggIds,
            List<DialogueMessage> transcript,
            Integer focusedBggId) {
        public ConversationRequest(RecommendationProfile profile, String message) {
            this(profile, message, List.of(), List.of(), null);
        }

        public ConversationRequest(RecommendationProfile profile, String message, List<Integer> excludedBggIds) {
            this(profile, message, excludedBggIds, List.of(), null);
        }

        public ConversationRequest {
            excludedBggIds = excludedBggIds == null ? List.of() : List.copyOf(excludedBggIds);
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
        }
    }

    public record RecommendationProfile(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            GameType type,
            InteractionPreference interaction) {
        public static RecommendationProfile empty() {
            return new RecommendationProfile(null, null, null, GameType.ALL, InteractionPreference.ANY);
        }
    }

    public record ConversationResponse(
            Outcome outcome,
            DecisionMode mode,
            String assistantMessage,
            RecommendationProfile profile,
            Clarification clarification,
            int sourceCount,
            int candidatesEvaluated,
            UserModelView userModel,
            List<ResearchSource> researchSources,
            HarnessTrace harness,
            List<RecommendedGame> games) {
        public ConversationResponse(
                Outcome outcome,
                DecisionMode mode,
                String assistantMessage,
                RecommendationProfile profile,
                Clarification clarification,
                int sourceCount,
                int candidatesEvaluated,
                List<RecommendedGame> games) {
            this(
                    outcome,
                    mode,
                    assistantMessage,
                    profile,
                    clarification,
                    sourceCount,
                    candidatesEvaluated,
                    new UserModelView("", List.of()),
                    List.of(),
                    new HarnessTrace(0, 0, 0, true, List.of()),
                    games);
        }

        public ConversationResponse {
            researchSources = List.copyOf(researchSources);
            games = List.copyOf(games);
        }
    }

    public record UserModelView(String summary, List<PreferenceHypothesisView> hypotheses) {
        public UserModelView {
            hypotheses = List.copyOf(hypotheses);
        }
    }

    public record PreferenceHypothesisView(String text, String confidence, String basedOn) {}

    public record ResearchSource(int index, String title, String url, String domain) {}

    public record HarnessTrace(
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            List<String> actions) {
        public HarnessTrace {
            actions = List.copyOf(actions);
        }
    }

    public record Clarification(PreferenceField field, String prompt, List<ClarificationOption> options) {
        public Clarification {
            options = List.copyOf(options);
        }
    }

    public record ClarificationOption(String value, String label) {}

    public record RecommendedGame(
            BrowseGame game,
            List<String> matches,
            List<String> tradeoffs,
            List<RecommendationReason> reasons) {
        public RecommendedGame(BrowseGame game, List<String> matches, List<String> tradeoffs) {
            this(game, matches, tradeoffs, List.of());
        }

        public RecommendedGame {
            matches = List.copyOf(matches);
            tradeoffs = List.copyOf(tradeoffs);
            reasons = List.copyOf(reasons);
        }
    }

    public record RecommendationReason(ReasonKind kind, String text, List<Integer> sourceIndexes) {
        public RecommendationReason {
            sourceIndexes = List.copyOf(sourceIndexes);
        }
    }

    public enum ReasonKind {
        BGG_FACT,
        PREFERENCE_INFERENCE,
        WEB_RESEARCH
    }

    public enum Outcome {
        NEEDS_CLARIFICATION,
        RECOMMENDATIONS,
        NO_MATCH,
        UNAVAILABLE
    }

    public enum DecisionMode {
        DETERMINISTIC,
        MODEL_ASSISTED
    }

    public enum PreferenceField {
        PLAYERS,
        DURATION,
        COMPLEXITY,
        CONVERSATION
    }

    public enum InteractionPreference {
        ANY,
        COMPETITIVE,
        COOPERATIVE,
        TEAM
    }
}
