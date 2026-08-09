package com.rulepilot.recommendation.application;

import com.rulepilot.recommendation.BoardGameRecommendationAdvisor;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueAct;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Plan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.ProfileView;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Slate;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoverySignal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.recommendation.application.BoardGamePreferenceDialogue.ResolvedTurn;
import com.rulepilot.recommendation.application.BoardGameReferenceIntent.ReferenceIntent;
import com.rulepilot.recommendation.application.BoardGameRecommendationSelector.CandidatePool;
import com.rulepilot.recommendation.application.BoardGameRecommendationSelector.SelectionStatus;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
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

    private final BoardGameRecommendationTools tools;
    private final BoardGamePreferenceDialogue dialogue;
    private final BoardGameRecommendationQueryCoverage queryCoverage;
    private final BoardGameReferenceIntent referenceIntent;
    private final BoardGameRecommendationCandidateAgent candidateAgent;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationAdvisor advisor;
    private final BoardGameRecommendationProperties properties;

    public BoardGameRecommendationAgent(
            BoardGameRecommendationTools tools,
            BoardGamePreferenceDialogue dialogue,
            BoardGameRecommendationQueryCoverage queryCoverage,
            BoardGameReferenceIntent referenceIntent,
            BoardGameRecommendationCandidateAgent candidateAgent,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationAdvisor advisor,
            BoardGameRecommendationProperties properties) {
        this.tools = tools;
        this.dialogue = dialogue;
        this.queryCoverage = queryCoverage;
        this.referenceIntent = referenceIntent;
        this.candidateAgent = candidateAgent;
        this.selector = selector;
        this.advisor = advisor;
        this.properties = properties;
    }

    public ConversationResponse converse(ConversationRequest input, String requestedLocale) {
        return converse(input, requestedLocale, ignored -> {});
    }

    public ConversationResponse converse(
            ConversationRequest input,
            String requestedLocale,
            Consumer<ProgressUpdate> progressListener) {
        long startedAt = System.nanoTime();
        Consumer<ProgressStage> progress = stage -> emitProgress(progressListener, stage, startedAt);
        progress.accept(ProgressStage.UNDERSTANDING_REQUEST);
        ConversationRequest request = validate(input);
        String locale = simplifiedChineseLocale(requestedLocale) ? "zh-CN" : "en";
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
        RetrievalPlan retrievalPlan = planned
                .map(plan -> queryCoverage.preserveUncoveredExpression(
                        plan.retrievalPlan(), request.message(), turn.profile()))
                .orElseGet(RetrievalPlan::empty);
        int sourceCount = tools.catalogGameCount();
        Optional<ReferenceIntent> agentReference = request.focusedBggId() == null
                ? planned.flatMap(plan -> referenceIntent.resolveAgent(
                        plan.referenceTitle(), request.transcript(), request.message()))
                : Optional.empty();
        Optional<ReferenceIntent> namedReference = request.focusedBggId() == null
                ? agentReference.or(() -> referenceIntent.resolve(request.transcript(), request.message()))
                : Optional.empty();
        if (agentReference.isPresent()) actions.add("INTERPRET_BGG_REFERENCE");
        Game resolvedNamedReference = null;
        if (namedReference.isPresent()) {
            progress.accept(ProgressStage.READING_GAME_DETAILS);
            catalogCalls++;
            ReferenceObservation observation = tools.resolveReferenceTitle(namedReference.orElseThrow().title());
            actions.add("RESOLVE_BGG_REFERENCE");
            if (!observation.resolved()) {
                return unresolvedReference(
                        turn,
                        locale,
                        namedReference.orElseThrow().title(),
                        observation.code(),
                        modelCalls,
                        catalogCalls,
                        researchCalls,
                        actions);
            }
            resolvedNamedReference = observation.games().getFirst();
            retrievalPlan = similarityRetrievalPlan(
                    retrievalPlan, resolvedNamedReference, namedReference.orElseThrow());
            userModel = referenceUserModel(resolvedNamedReference, locale);
        }
        boolean plannedPreferenceSignal = planned.isPresent()
                && (!retrievalPlan.features().isEmpty()
                        || !planned.orElseThrow().userModel().hypotheses().isEmpty());

        if (resolvedNamedReference == null
                && planned.filter(plan -> plan.act() == DialogueAct.RESPOND).isPresent()) {
            Plan plan = planned.orElseThrow();
            return response(
                    Outcome.CONVERSATION,
                    DecisionMode.MODEL_ASSISTED,
                    plan.assistantMessage(),
                    turn.profile(),
                    null,
                    0,
                    0,
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, false, actions),
                    List.of());
        }

        if (resolvedNamedReference == null
                && planned.filter(plan -> plan.act() == DialogueAct.ASK).isPresent()) {
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
        if (planned.filter(plan -> plan.act() == DialogueAct.EXPLAIN).isPresent()
                && request.focusedBggId() == null
                && resolvedNamedReference == null) {
            Plan plan = planned.orElseThrow();
            String question = !plan.nextQuestion().isBlank()
                    ? plan.nextQuestion()
                    : chinese(locale) ? "你想继续了解刚才哪一款？" : "Which game would you like to keep discussing?";
            String message = !plan.assistantMessage().isBlank() ? plan.assistantMessage() : question;
            return response(
                    Outcome.NEEDS_CLARIFICATION,
                    DecisionMode.MODEL_ASSISTED,
                    message,
                    turn.profile(),
                    conversationalClarification(question),
                    0,
                    0,
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, false, actions),
                    List.of());
        }
        boolean modelWantsCandidateWork = planned
                .map(plan -> plan.act() == DialogueAct.RECOMMEND || plan.act() == DialogueAct.EXPLAIN)
                .orElse(false) || resolvedNamedReference != null;
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

        boolean focusedDiscussion = request.focusedBggId() != null
                && planned.map(plan -> plan.act() == DialogueAct.EXPLAIN).orElse(true);
        Game namedReferenceGame = resolvedNamedReference;
        List<Integer> effectiveExcludedBggIds = java.util.stream.Stream.concat(
                        request.excludedBggIds().stream(),
                        java.util.stream.Stream.of(
                                        !focusedDiscussion && request.focusedBggId() != null
                                                ? request.focusedBggId()
                                                : null,
                                        namedReferenceGame == null
                                                ? null
                                                : namedReferenceGame.ranking().bggId())
                                .filter(Objects::nonNull))
                        .distinct()
                        .limit(60)
                        .toList();

        List<Game> sourceGames = List.of();
        Game referenceGame = resolvedNamedReference;
        if (focusedDiscussion) {
            progress.accept(ProgressStage.READING_GAME_DETAILS);
            catalogCalls++;
            CatalogObservation catalogObservation = tools.lookupGame(request.focusedBggId());
            actions.add(catalogObservation.tool().name());
            if (!catalogObservation.succeeded()) {
                return unavailable(turn, locale, userModel, modelCalls, catalogCalls, researchCalls, actions);
            }
            sourceGames = catalogObservation.games();
            sourceCount = catalogObservation.sourceCount();
        } else {
            if (request.focusedBggId() != null) {
                progress.accept(ProgressStage.READING_GAME_DETAILS);
                catalogCalls++;
                CatalogObservation referenceObservation = tools.lookupGame(request.focusedBggId());
                actions.add("LOOKUP_REFERENCE_GAME");
                if (referenceObservation.succeeded() && !referenceObservation.games().isEmpty()) {
                    referenceGame = referenceObservation.games().getFirst();
                }
            }
        }

        retrievalPlan = referenceGame == null || namedReference.isPresent()
                ? retrievalPlan
                : similarityRetrievalPlan(retrievalPlan, referenceGame, null);
        List<Integer> discoveredBggIds = List.of();
        Research candidateDiscoveryEvidence = Research.empty();
        boolean requiredUserExpression = !focusedDiscussion && retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source()
                        == BoardGameRecommendationAdvisor.FeatureSource.USER_EXPRESSION
                        && feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED);
        boolean requiredUserExpressionVerified = false;
        boolean featureFirstDiscovery = !focusedDiscussion
                && !retrievalPlan.features().isEmpty()
                && (retrievalPlan.candidateDiscoveryRequested()
                        || retrievalPlan.features().stream().anyMatch(feature ->
                                feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED))
                && tools.webResearchConfigured();
        boolean nativeCandidateDiscovery = !focusedDiscussion
                && candidateAgent.configured()
                && needsNativeCandidateDiscovery(retrievalPlan);
        boolean nativeCandidateSucceeded = false;
        if (nativeCandidateDiscovery) {
            progress.accept(ProgressStage.SELECTING_TOOLS);
            BoardGameRecommendationCandidateAgent.Result discovered =
                    candidateAgent.discover(
                            retrievalPlan,
                            turn.profile(),
                            locale,
                            step -> progress.accept(switch (step) {
                                case MODEL_SELECTING -> ProgressStage.SELECTING_TOOLS;
                                case SEARCHING_NAMES -> ProgressStage.SEARCHING_BGG_CATALOG;
                                case LOOKING_UP_DETAILS -> ProgressStage.VERIFYING_BGG_CANDIDATES;
                            }));
            modelCalls += discovered.modelCalls();
            catalogCalls += (int) discovered.actions().stream()
                    .filter(action -> action.equals("SEARCH_BGG_BY_NAME")
                            || action.equals("LOOKUP_BGG_CANDIDATES"))
                    .count();
            actions.addAll(discovered.actions());
            sourceGames = discovered.games();
            discoveredBggIds = sourceGames.stream()
                    .map(game -> game.ranking().bggId())
                    .toList();
            nativeCandidateSucceeded = discovered.succeeded();
            featureFirstDiscovery = false;
        }
        if (!nativeCandidateDiscovery || !nativeCandidateSucceeded) {
            featureFirstDiscovery = !focusedDiscussion
                    && !retrievalPlan.features().isEmpty()
                    && (retrievalPlan.candidateDiscoveryRequested()
                            || retrievalPlan.features().stream().anyMatch(feature ->
                                    feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED))
                    && tools.webResearchConfigured();
        }
        if (featureFirstDiscovery && sourceGames.isEmpty()) {
            researchCalls++;
            actions.add("DISCOVER_CANDIDATES");
            CandidateDiscoveryResult discovered = discoverCandidates(retrievalPlan, locale, progress);
            if (discovered.lookupAttempted()) {
                catalogCalls++;
                actions.add("LOOKUP_BGG_CANDIDATES");
            }
            discoveredBggIds = discovered.bggIds();
            sourceGames = discovered.games();
            candidateDiscoveryEvidence = discovered.evidence();
            requiredUserExpressionVerified = !candidateDiscoveryEvidence.games().isEmpty();
            if (!candidateDiscoveryEvidence.games().isEmpty()) actions.add("RESEARCH_GAME_FIT");
        } else if (!focusedDiscussion && !nativeCandidateDiscovery) {
            progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
            catalogCalls++;
            CatalogObservation catalogObservation = tools.searchCatalog(
                    turn.profile().type(), retrievalPlan.candidateTypes(), properties.modelCandidateLimit());
            actions.add(catalogObservation.tool().name());
            if (!catalogObservation.succeeded()) {
                return unavailable(turn, locale, userModel, modelCalls, catalogCalls, researchCalls, actions);
            }
            sourceGames = catalogObservation.games();
            sourceCount = catalogObservation.sourceCount();
        }
        CandidatePool pool = selector.prepare(
                sourceGames, turn.profile(), effectiveExcludedBggIds, retrievalPlan, discoveredBggIds);
        if (nativeCandidateSucceeded && pool.candidates().size() < properties.resultCount()) {
            progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
            catalogCalls++;
            CatalogObservation catalogBackfill = tools.searchCatalog(
                    turn.profile().type(), retrievalPlan.candidateTypes(), properties.modelCandidateLimit());
            actions.add("SEARCH_BGG_CATALOG_BACKFILL");
            if (catalogBackfill.succeeded()) {
                sourceCount = catalogBackfill.sourceCount();
                sourceGames = mergeCandidates(
                        sourceGames,
                        catalogBackfill.games(),
                        Math.min(20, properties.modelCandidateLimit() * 2));
                pool = selector.prepare(
                        sourceGames,
                        turn.profile(),
                        effectiveExcludedBggIds,
                        retrievalPlan,
                        discoveredBggIds);
            }
        }
        boolean nativeCandidatesComplete = nativeCandidateSucceeded
                && pool.status() == SelectionStatus.READY;
        if (!featureFirstDiscovery
                && !focusedDiscussion
                && (referenceGame != null
                        || (!nativeCandidatesComplete && shouldDiscoverCandidates(retrievalPlan, pool)))
                && tools.webResearchConfigured()) {
            progress.accept(ProgressStage.DISCOVERING_CANDIDATES);
            researchCalls++;
            actions.add("DISCOVER_CANDIDATES");
            Optional<CandidateDiscovery> discovery = tools.discoverCandidates(discoveryRequest(retrievalPlan, locale))
                    .result();
            if (discovery.isPresent()) {
                CandidateDiscovery completedDiscovery = discovery.orElseThrow();
                discoveredBggIds = completedDiscovery.candidates().stream()
                        .map(BoardGameRecommendationWebResearch.CandidateLead::bggId)
                        .distinct()
                        .limit(12)
                        .toList();
                if (!discoveredBggIds.isEmpty()) {
                    progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
                    catalogCalls++;
                    CatalogObservation discoveredLookup = tools.lookupCandidates(discoveredBggIds);
                    actions.add(discoveredLookup.tool().name());
                    if (discoveredLookup.succeeded()) {
                        List<Game> discoveredGames = discoveredLookup.games();
                        candidateDiscoveryEvidence = discoveryEvidence(
                                completedDiscovery, discoveredGames, retrievalPlan);
                        requiredUserExpressionVerified = !candidateDiscoveryEvidence.games().isEmpty();
                        sourceGames = requiredUserExpression
                                ? discoveredGames
                                : mergeCandidates(discoveredGames, sourceGames, properties.modelCandidateLimit());
                        if (!candidateDiscoveryEvidence.games().isEmpty()) actions.add("RESEARCH_GAME_FIT");
                        pool = selector.prepare(
                                sourceGames,
                                turn.profile(),
                                effectiveExcludedBggIds,
                                retrievalPlan,
                                discoveredBggIds);
                    } else {
                        LOGGER.warn("Discovered BGG candidate lookup failed; keeping structured catalog candidates");
                    }
                }
            }
        }
        if (requiredUserExpression
                && tools.webResearchConfigured()
                && !requiredUserExpressionVerified) {
            return response(
                    Outcome.NO_MATCH,
                    mode(planned),
                    chinese(locale)
                            ? "我没有找到同时有来源支持、又能通过 BGG ID 和硬条件验证的候选，所以没有拿热门榜单游戏来凑数。可以换一种说法，或告诉我最接近的机制。"
                            : "I found no source-supported candidate that also passed BGG ID and hard-constraint verification, so I did not pad the result with popular games. Try another phrase or name the closest mechanism.",
                    turn.profile(),
                    null,
                    sourceCount,
                    pool.candidatesEvaluated(),
                    userModel,
                    List.of(),
                    new HarnessTrace(modelCalls, catalogCalls, researchCalls, false, actions),
                    List.of());
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
        boolean researchUseful = planned.filter(plan -> researchJustified(
                        plan, focusedDiscussion, nativeCandidatesComplete, request.message()))
                .isPresent();
        if (researchUseful && research.games().isEmpty() && tools.webResearchConfigured()) {
            progress.accept(ProgressStage.RESEARCHING_GAME_FIT);
            List<BoardGameRecommendationAdvisor.Candidate> candidates = selector.advisorCandidates(pool).stream()
                    .limit(5)
                    .toList();
            researchCalls++;
            String researchQuestion = focusedDiscussion
                    ? planned.map(Plan::researchQuestion).orElse("")
                    : "";
            Optional<Research> researched = tools.researchGameFit(candidates, locale, researchQuestion).result();
            if (researched.isPresent()) {
                research = researched.get();
                actions.add(researchQuestion.isBlank() ? "RESEARCH_GAME_FIT" : "RESEARCH_GAME_QUESTION");
            }
        }

        CandidatePool completedPool = pool;
        Game completedReferenceGame = referenceGame;
        RetrievalPlan completedRetrievalPlan = retrievalPlan;
        boolean fastNativeResponse = nativeCandidatesComplete
                && !focusedDiscussion
                && !researchUseful;
        boolean evidenceBoundFocusedResponse = focusedDiscussion && !researchUseful;
        boolean structuredRanking = fastNativeResponse || evidenceBoundFocusedResponse || canUseStructuredRanking(
                request, planned, completedRetrievalPlan, research, researchUseful);
        Optional<Slate> slate = Optional.empty();
        if (planned.isPresent() && !structuredRanking) {
            progress.accept(ProgressStage.COMPOSING_RESPONSE);
            modelCalls++;
            slate = safeCompose(new BoardGameRecommendationAdvisor.CompositionRequest(
                    request.transcript(),
                    profile(turn.profile()),
                    userModel,
                    selector.advisorCandidates(completedPool),
                    research,
                    request.focusedBggId(),
                    locale,
                    completedReferenceGame != null && request.focusedBggId() == null
                            ? DialogueAct.RECOMMEND
                            : planned.orElseThrow().act(),
                    completedReferenceGame == null ? null : selector.advisorCandidate(completedReferenceGame)));
            if (slate.isPresent()) {
                actions.add(focusedDiscussion ? "COMPOSE_GAME_RESPONSE" : "COMPOSE_RECOMMENDATIONS");
            }
        }
        if (structuredRanking) actions.add("RANK_STRUCTURED_CANDIDATES");

        Research completedResearch = research;
        List<RecommendedGame> selectedGames = slate
                .map(value -> selector.fromSlate(
                        completedPool, value, turn.profile(), chinese(locale), completedResearch))
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> selector.fallback(
                        completedPool, turn.profile(), chinese(locale), completedResearch));
        int requestedResultCount = requestedResultCount(request.message(), selectedGames.size());
        List<RecommendedGame> games = selectedGames.stream().limit(requestedResultCount).toList();
        String fastNativeSummary = quickRecommendationSummary(locale, games);
        boolean fallback = slate.isEmpty() && !structuredRanking;
        String assistantMessage = slate.map(Slate::assistantMessage)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> completedReferenceGame != null && request.focusedBggId() == null
                        ? referenceSummary(locale, completedReferenceGame)
                        : structuredRanking
                        ? fastNativeResponse
                                ? fastNativeSummary
                                : evidenceBoundFocusedResponse
                                        ? focusedGameSummary(locale, games, request.message())
                                        : structuredSummary(locale, completedRetrievalPlan)
                        : fallbackSummary(locale, turn.clarification()));
        String nextQuestion = slate.map(Slate::nextQuestion)
                .filter(value -> !value.isBlank())
                .orElse("");
        if (nextQuestion.isBlank()
                && planned.isPresent()
                && !focusedDiscussion
                && completedReferenceGame == null) {
            nextQuestion = planned.orElseThrow().nextQuestion();
        }
        Clarification responseClarification = !nextQuestion.isBlank()
                ? conversationalClarification(nextQuestion)
                : focusedDiscussion ? null : turn.clarification();
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

    private void emitProgress(
            Consumer<ProgressUpdate> listener,
            ProgressStage stage,
            long startedAt) {
        if (listener == null) return;
        try {
            listener.accept(new ProgressUpdate(stage, (System.nanoTime() - startedAt) / 1_000_000));
        } catch (RuntimeException exception) {
            LOGGER.debug("Recommendation progress listener stopped accepting updates");
        }
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

    private boolean canUseStructuredRanking(
            ConversationRequest request,
            Optional<Plan> planned,
            RetrievalPlan retrievalPlan,
            Research research,
            boolean researchUseful) {
        if (planned.isEmpty()
                || request.focusedBggId() != null
                || !request.excludedBggIds().isEmpty()) return false;
        boolean requiredMetadata = retrievalPlan.features().stream().anyMatch(feature ->
                feature.source() == BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA
                        && feature.mode() == BoardGameRecommendationAdvisor.FeatureMode.REQUIRED);
        boolean experienceSignal = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
        boolean verifiedUserExpression = retrievalPlan.features().stream()
                        .anyMatch(feature -> feature.source()
                                == BoardGameRecommendationAdvisor.FeatureSource.USER_EXPRESSION)
                && !research.games().isEmpty();
        boolean metadataOnly = requiredMetadata && !experienceSignal && research.games().isEmpty();
        return (metadataOnly || verifiedUserExpression) && !researchUseful;
    }

    private boolean experienceResearchJustified(Plan plan) {
        return plan.researchRequested() && plan.retrievalPlan().features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.EXPERIENCE);
    }

    private boolean researchJustified(
            Plan plan,
            boolean focusedGame,
            boolean nativeCandidatesComplete,
            String latestMessage) {
        if (focusedGame) return plan.researchRequested();
        if (nativeCandidatesComplete && !explicitResearchRequest(latestMessage)) return false;
        return experienceResearchJustified(plan);
    }

    private boolean explicitResearchRequest(String message) {
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*(?:查(?:一下|查)?|搜(?:一下)?|口碑|评价|测评|真实体验|玩家怎么说|"
                + "\\bresearch\\b|\\breviews?\\b|\\blook (?:it )?up\\b|\\bwhat do players say\\b).*");
    }

    private boolean shouldDiscoverCandidates(RetrievalPlan retrievalPlan, CandidatePool pool) {
        boolean unresolvedUserExpression = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() == BoardGameRecommendationAdvisor.FeatureSource.USER_EXPRESSION);
        if (retrievalPlan.features().isEmpty()) return false;
        if (unresolvedUserExpression || pool.status() != SelectionStatus.READY) return true;
        if (!retrievalPlan.candidateDiscoveryRequested()) return false;
        boolean experienceDriven = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() != BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA);
        return experienceDriven;
    }

    private boolean needsNativeCandidateDiscovery(RetrievalPlan retrievalPlan) {
        return retrievalPlan.candidateDiscoveryRequested() || !retrievalPlan.features().isEmpty();
    }

    private RetrievalPlan similarityRetrievalPlan(
            RetrievalPlan planned,
            Game reference,
            ReferenceIntent namedReference) {
        if (reference.details() == null) return planned;
        List<BoardGameRecommendationAdvisor.FeatureConstraint> derived = java.util.stream.Stream.concat(
                        reference.details().categories().stream().limit(4),
                        reference.details().mechanics().stream().limit(4))
                .filter(value -> value != null && !value.isBlank())
                .map(value -> new BoardGameRecommendationAdvisor.FeatureConstraint(
                        value,
                        BoardGameRecommendationAdvisor.FeatureMode.PREFERRED,
                        BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA,
                        "reference: " + reference.ranking().sourceName()))
                .toList();
        java.util.stream.Stream<BoardGameRecommendationAdvisor.FeatureConstraint> retained = planned.features().stream()
                .filter(feature -> namedReference == null || !derivedFromUnresolvedReference(feature, namedReference));
        List<BoardGameRecommendationAdvisor.FeatureConstraint> merged = java.util.stream.Stream.concat(
                        retained, derived.stream())
                .filter(feature -> feature != null)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                feature -> feature.term().toLowerCase(java.util.Locale.ROOT) + ":" + feature.mode(),
                                java.util.function.Function.identity(),
                                (left, right) -> left,
                                java.util.LinkedHashMap::new),
                        values -> values.values().stream().limit(8).toList()));
        return new RetrievalPlan(planned.candidateTypes(), merged, true);
    }

    private boolean derivedFromUnresolvedReference(
            BoardGameRecommendationAdvisor.FeatureConstraint feature,
            ReferenceIntent reference) {
        String title = reference.normalizedTitle();
        String term = normalizedForReference(feature.term());
        String basedOn = normalizedForReference(feature.basedOn());
        String sourceMessage = normalizedForReference(reference.basedOn());
        return feature.source() == BoardGameRecommendationAdvisor.FeatureSource.USER_EXPRESSION
                || term.contains(title)
                || basedOn.contains(title)
                || !sourceMessage.isBlank() && basedOn.equals(sourceMessage);
    }

    private String normalizedForReference(String value) {
        return java.text.Normalizer.normalize(
                        value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }

    private DiscoveryRequest discoveryRequest(RetrievalPlan retrievalPlan, String locale) {
        List<DiscoverySignal> signals = retrievalPlan.features().stream()
                .map(feature -> new DiscoverySignal(feature.term(), feature.mode(), feature.source()))
                .toList();
        return new DiscoveryRequest(signals, retrievalPlan.candidateTypes(), locale);
    }

    private CandidateDiscoveryResult discoverCandidates(
            RetrievalPlan retrievalPlan,
            String locale,
            Consumer<ProgressStage> progress) {
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES);
        Optional<CandidateDiscovery> discovery = tools.discoverCandidates(discoveryRequest(retrievalPlan, locale))
                .result();
        if (discovery.isEmpty()) return CandidateDiscoveryResult.empty(false);
        CandidateDiscovery completed = discovery.orElseThrow();
        List<Integer> ids = completed.candidates().stream()
                .map(BoardGameRecommendationWebResearch.CandidateLead::bggId)
                .distinct()
                .limit(12)
                .toList();
        if (ids.isEmpty()) return CandidateDiscoveryResult.empty(false);
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        CatalogObservation lookup = tools.lookupCandidates(ids);
        if (!lookup.succeeded()) return CandidateDiscoveryResult.empty(true);
        List<Game> games = lookup.games();
        return new CandidateDiscoveryResult(
                true,
                ids,
                games,
                discoveryEvidence(completed, games, retrievalPlan));
    }

    private List<Game> mergeCandidates(
            List<Game> discovered, List<Game> structured, int maximum) {
        java.util.LinkedHashMap<Integer, Game> merged = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(discovered.stream(), structured.stream())
                .forEach(game -> merged.putIfAbsent(game.ranking().bggId(), game));
        return merged.values().stream().limit(maximum).toList();
    }

    private record CandidateDiscoveryResult(
            boolean lookupAttempted,
            List<Integer> bggIds,
            List<Game> games,
            Research evidence) {
        private CandidateDiscoveryResult {
            bggIds = List.copyOf(bggIds);
            games = List.copyOf(games);
            evidence = evidence == null ? Research.empty() : evidence;
        }

        private static CandidateDiscoveryResult empty(boolean lookupAttempted) {
            return new CandidateDiscoveryResult(
                    lookupAttempted, List.of(), List.of(), Research.empty());
        }
    }

    private Research discoveryEvidence(
            CandidateDiscovery discovery, List<Game> verifiedGames, RetrievalPlan retrievalPlan) {
        boolean experienceDriven = retrievalPlan.features().stream()
                .anyMatch(feature -> feature.source() != BoardGameRecommendationAdvisor.FeatureSource.BGG_METADATA);
        if (!experienceDriven) return Research.empty();
        java.util.Set<Integer> verifiedIds = verifiedGames.stream()
                .map(game -> game.ranking().bggId())
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

    private ConversationResponse unresolvedReference(
            ResolvedTurn turn,
            String locale,
            String title,
            String code,
            int modelCalls,
            int catalogCalls,
            int researchCalls,
            List<String> actions) {
        boolean unavailable = "CATALOG_UNAVAILABLE".equals(code) || "REFERENCE_DETAILS_UNAVAILABLE".equals(code);
        String question = unavailable
                ? chinese(locale)
                        ? "BGG 暂时没有返回《" + title + "》的完整资料，所以我先不猜它的玩法。可以稍后直接重试。"
                        : "BGG did not return complete details for “" + title
                                + "”, so I will not guess its gameplay. Please retry shortly."
                : chinese(locale)
                        ? "我先在 BGG 里核对了《" + title
                                + "》，但没有找到唯一的精确对应，所以先不猜它的玩法。请补充原文名或出版年份，我会再查一次。"
                        : "I checked BGG for “" + title
                                + "” but did not find one exact match, so I will not guess its gameplay. Add the original title or publication year and I will check again.";
        return response(
                unavailable ? Outcome.UNAVAILABLE : Outcome.NEEDS_CLARIFICATION,
                DecisionMode.DETERMINISTIC,
                question,
                turn.profile(),
                unavailable ? null : conversationalClarification(question),
                tools.catalogGameCount(),
                0,
                emptyUserModel(),
                List.of(),
                new HarnessTrace(modelCalls, catalogCalls, researchCalls, false, actions),
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

    private UserModel referenceUserModel(Game reference, String locale) {
        String title = referenceTitle(reference, locale);
        return new UserModel(
                chinese(locale)
                        ? "正在以 BGG 已核对的《" + title + "》作为参照；相似点只从实际目录资料中提取。"
                        : "Using the BGG-verified “" + title
                                + "” as the reference; similarity is derived only from observed catalog metadata.",
                List.of());
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

    private String referenceSummary(String locale, Game reference) {
        String title = referenceTitle(reference, locale);
        if (chinese(locale)) {
            return "我先在 BGG 核对了《" + title + "》，再按它实际记录的机制和类型"
                    + "找相似候选；没有沿用对话里未经来源验证的玩法猜测。下面每张卡片会分别说明真正重合的点和取舍。";
        }
        return "I verified “" + title + "” in BGG first, then searched by its observed mechanics and categories"
                + ". I discarded any earlier unsupported guesses; each card shows the concrete overlap and tradeoff.";
    }

    private String referenceTitle(Game game, String locale) {
        if (chinese(locale)
                && game.details() != null
                && !game.details().officialChineseName().isBlank()) {
            String original = game.ranking().sourceName();
            return game.details().officialChineseName().equalsIgnoreCase(original)
                    ? original
                    : game.details().officialChineseName() + "（" + original + "）";
        }
        return game.ranking().sourceName();
    }

    private String structuredSummary(String locale, RetrievalPlan retrievalPlan) {
        String userExpression = retrievalPlan.features().stream()
                .filter(feature -> feature.source()
                        == BoardGameRecommendationAdvisor.FeatureSource.USER_EXPRESSION)
                .map(BoardGameRecommendationAdvisor.FeatureConstraint::term)
                .findFirst()
                .orElse("");
        if (!userExpression.isBlank()) {
            return chinese(locale)
                    ? "我保留了你的原始说法“" + userExpression
                            + "”，通过公开资料发现候选，再用 BGG ID、人数和时长验证；下面只展示通过验证的结果。"
                    : "I preserved your wording “" + userExpression
                            + "”, discovered candidates from public sources, then verified their BGG IDs, player counts, and play times. Only verified results are shown.";
        }
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

    private String quickRecommendationSummary(String locale, List<RecommendedGame> games) {
        String titles = games.stream()
                .limit(3)
                .map(game -> quickRecommendationTitle(game, locale))
                .collect(java.util.stream.Collectors.joining(chinese(locale) ? "、" : ", "));
        if (titles.isBlank()) return structuredSummary(locale, RetrievalPlan.empty());
        return chinese(locale)
                ? "条件我记住了。这轮先看" + titles
                        + "；为什么适合、哪里可能不合口味，我都放在卡片里。你不用重新报条件，直接说哪款太重、太安静或不够对抗，我就沿着你的反馈继续换。"
                : "I kept your constraints. Start with " + titles
                        + "; each card explains the fit and the tradeoffs. You do not need to repeat yourself—tell me which option feels too heavy, too quiet, or not interactive enough, and I will adjust from there.";
    }

    private String quickRecommendationTitle(RecommendedGame game, String locale) {
        if (chinese(locale)
                && game.game().details() != null
                && game.game().details().officialChineseName() != null
                && !game.game().details().officialChineseName().isBlank()) {
            return "《" + game.game().details().officialChineseName() + "》";
        }
        String sourceName = game.game().ranking().sourceName();
        return chinese(locale) ? "《" + sourceName + "》" : sourceName;
    }

    private int requestedResultCount(String message, int available) {
        if (available <= 0) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?iu)(?:换|推荐|找|来|给(?:我)?)(?:\\s*(?:一批|一组))?\\s*([一二两三四五1-5])\\s*款|"
                                + "\\b(?:give|show|find|recommend|try)\\s+(one|two|three|four|five|[1-5])\\s+(?:more\\s+)?(?:games?|options?)\\b")
                .matcher(message == null ? "" : message);
        if (!matcher.find()) return available;
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        int requested = switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "一", "one" -> 1;
            case "二", "两", "two" -> 2;
            case "三", "three" -> 3;
            case "四", "four" -> 4;
            case "五", "five" -> 5;
            default -> Integer.parseInt(value);
        };
        return Math.min(requested, available);
    }

    private String focusedGameSummary(String locale, List<RecommendedGame> games, String latestMessage) {
        if (games.isEmpty()) return structuredSummary(locale, RetrievalPlan.empty());
        RecommendedGame game = games.getFirst();
        String title = quickRecommendationTitle(game, locale);
        String facts = game.matches().stream()
                .filter(value -> !value.contains("总榜") && !value.contains("overall rank"))
                .limit(3)
                .collect(java.util.stream.Collectors.joining(chinese(locale) ? "；" : "; "));
        boolean asksForRules = (latestMessage == null ? "" : latestMessage).matches(
                "(?is).*(?:怎么玩|怎么进行|规则|回合|流程|how (?:do you )?play|turn|rules?).*");
        if (chinese(locale)) {
            String verified = facts.isBlank() ? "我目前只能确认它的 BGG 目录资料" : facts;
            return asksForRules
                    ? "先把能确认的说清楚：" + title + "——" + verified
                            + "。至于具体回合和卡牌效果，BGG 简介不能代替规则书；选中这款后导入官方规则书，答疑会按页给你讲，不会把简介猜成规则。"
                    : title + "目前能确认的适配点是：" + verified
                            + "。卡片里列出了玩法标签和取舍；如果要追问具体回合，请进入规则书答疑，我会按页回答。";
        }
        String verified = facts.isBlank() ? "I can currently verify only its BGG catalog metadata" : facts;
        return asksForRules
                ? "Here is what I can verify about " + title + ": " + verified
                        + ". A BGG description is not a rulebook, so import the official rulebook for a page-cited turn walkthrough instead of a guessed rules explanation."
                : "Here is the verified fit for " + title + ": " + verified
                        + ". The card shows its play tags and tradeoffs; use rulebook Q&A for a page-cited turn explanation.";
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

    private boolean simplifiedChineseLocale(String locale) {
        String value = locale == null ? "" : locale.strip().toLowerCase(java.util.Locale.ROOT);
        return value.equals("zh") || value.equals("zh-cn") || value.equals("zh-hans");
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

    public enum ProgressStage {
        UNDERSTANDING_REQUEST,
        SELECTING_TOOLS,
        SEARCHING_BGG_CATALOG,
        READING_GAME_DETAILS,
        DISCOVERING_CANDIDATES,
        VERIFYING_BGG_CANDIDATES,
        RESEARCHING_GAME_FIT,
        COMPOSING_RESPONSE
    }

    public record ProgressUpdate(ProgressStage stage, long elapsedMs) {
        public ProgressUpdate {
            Objects.requireNonNull(stage, "progress stage is required");
            if (elapsedMs < 0) throw new IllegalArgumentException("elapsedMs must not be negative");
        }
    }

    public record RecommendationProfile(
            Integer players,
            Integer maxMinutes,
            BigDecimal maxWeight,
            BggGameType type,
            InteractionPreference interaction) {
        public static RecommendationProfile empty() {
            return new RecommendationProfile(null, null, null, BggGameType.ALL, InteractionPreference.ANY);
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
            Game game,
            List<String> matches,
            List<String> tradeoffs,
            List<RecommendationReason> reasons) {
        public RecommendedGame(Game game, List<String> matches, List<String> tradeoffs) {
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
        CONVERSATION,
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
