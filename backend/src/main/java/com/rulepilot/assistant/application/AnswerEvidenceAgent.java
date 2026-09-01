package com.rulepilot.assistant.application;

import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.TerminalContract;
import com.rulepilot.assistant.NativeToolAgent.TerminalStatus;
import com.rulepilot.assistant.NativeToolEvidenceHandles;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.AnswerEvidencePolicy;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.AnswerEvidenceSelectionPolicy;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Refines source evidence through native read tools; model text is never returned to the player. */
@Service
@Profile("!test")
public class AnswerEvidenceAgent implements AnswerEvidenceRefiner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerEvidenceAgent.class);
    private static final int MAX_OBSERVED_EVIDENCE = 24;
    private static final String SYSTEM_PROMPT = """
            You are the evidence-refinement stage of a board-game rules assistant. Never answer the player and never
            rely on rule knowledge outside the supplied evidence and tool observations. The application has already
            run deterministic retrieval. When the request lists prior cited pages to re-read, call read_rule_pages
            once for exactly those pages; do not search again because the prior answer is only a provenance hint and
            the fresh page observation is the current-turn evidence. Otherwise use the read-only rulebook tools only
            to fill an uncovered condition,
            exception, list item, follow-up dependency, visual-reference dependency, or empty-result gap. Search one
            bounded need at a time. When general and special rules may conflict, search_rule_relationships can locate
            candidate exception, replacement, precedence, and conditional passages. Its cue labels are non-authoritative:
            compare the canonical excerpts and their applicability. When one retrieved excerpt may omit an adjacent
            condition, list continuation, or exception, expand_rule_evidence_context can read the nearby canonical
            chunks around that evidence handle. Nearby text is not automatically applicable. After searching or
            expanding context, read the exact pages before deciding. For a question about a
            visible icon, label, table, diagram, arrow, or board layout,
            read_visual_page_facts may help locate literal printed content, but those facts have no mechanical-rule
            authority. Read exact pages after search or visual inspection to confirm the passages that cover the
            missing need. For an ADVICE evidence need, locate source-authored recommendations, cautions, priorities,
            or tips and preserve their stated faction, player-count, matchup, phase, and situation scope. A victory
            condition, scoring route, or legal action is not itself advice. Do not declare advice covered merely
            because the rules explain how points are earned. Choose retrieval queries from the accepted structured
            subquestion and the active document observations; do not use an application-supplied vocabulary list or
            repeatedly paraphrase the player. A sentence that merely points to another resource does not supply the
            requested guidance. Once a candidate actually expresses guidance, read its exact page. When the structured
            plan requests an example, acquire the complete cited setup, action, and outcome, and do not turn that example
            into a general rule. For a compound question,
            check every requested condition, sequence, exception, and complete-list obligation separately. A result
            requested as a concrete calculation requires an exact-page check of the governing numerical clause. Keep
            the counted object, aggregation unit, per-item or per-category scope, repetition count, multiplier, cap,
            and exception together. When that page supplies a worked example in the same scope, use its stated inputs,
            operation, and total as a consistency check; never detach a local per-item sentence from its governing
            preamble or silently discard a multiplier. A result
            count does not prove coverage: if a broad search misses one obligation, search that obligation again with
            the player's distinctive wording before reading the best candidate page. Stop requesting tools after the
            useful exact pages have been read. Any terminal prose is ignored by the application; only canonical page
            observations can become answer evidence. Do not invent identifiers or scope.
            For an ADVICE-only request, after reading the exact candidate page return exactly
            {"status":"EVIDENCE_READY"} only when that page itself contains source-authored guidance in the requested
            scope. Otherwise return exactly {"status":"EVIDENCE_NOT_FOUND"}. A scoring rule, victory condition, legal
            action, or statement that another resource has tips must use EVIDENCE_NOT_FOUND.
            For a COMPLETE_LIST obligation, one exact-page read does not by itself prove completeness. After reading a
            candidate page, verify that the canonical pages explicitly enumerate the requested list or jointly cover
            every requested item. Continue searching for the missing list-level rule when they do not. Use the same
            one-field JSON terminal object with EVIDENCE_READY only after that coverage check; if the bounded evidence
            cannot establish completeness, use EVIDENCE_NOT_FOUND. Never wrap the JSON in prose or markdown.
            The current player question is authoritative. Selected reference context may resolve an omitted subject,
            but it may not replace an object explicitly named in the current question. A player-supplied page number
            is only a scoped locator to inspect, never evidence that the page entails the requested rule.
            """;

    private final NativeToolAgent nativeAgent;
    private final RuleEvidenceLookup evidenceLookup;
    private final NativeToolScopes scopes;
    private final RuleAnswerRateLimiter rateLimiter;

    @Autowired
    public AnswerEvidenceAgent(
            NativeToolAgent nativeAgent,
            RuleEvidenceLookup evidenceLookup,
            NativeToolScopes scopes,
            RuleAnswerRateLimiter rateLimiter) {
        this.nativeAgent = nativeAgent;
        this.evidenceLookup = evidenceLookup;
        this.scopes = scopes;
        this.rateLimiter = rateLimiter;
    }

    public AnswerEvidenceAgent(
            NativeToolAgent nativeAgent,
            RuleEvidenceLookup evidenceLookup,
            DocumentNativeToolScopeFactory scopes,
            RuleAnswerRateLimiter rateLimiter) {
        this(nativeAgent, evidenceLookup, (NativeToolScopes) scopes, rateLimiter);
    }

    @Override
    public AnswerEvidenceRetriever.Result refine(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            AnswerEvidenceRetriever.Result deterministic) {
        return refine(
                assistantRunId,
                question,
                context,
                username,
                gameSessionId,
                AnswerQuestionPlan.fallback(question),
                deterministic);
    }

    @Override
    public AnswerEvidenceRetriever.Result refine(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            AnswerQuestionPlan questionPlan,
            AnswerEvidenceRetriever.Result deterministic) {
        return refine(
                assistantRunId,
                question,
                context,
                username,
                gameSessionId,
                questionPlan,
                deterministic,
                CaptureHandle.noop());
    }

    @Override
    public AnswerEvidenceRetriever.Result refine(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            AnswerQuestionPlan questionPlan,
            AnswerEvidenceRetriever.Result deterministic,
            CaptureHandle capture) {
        if (!AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, questionPlan, deterministic)) {
            return deterministic;
        }
        Set<String> requiredEvidenceTools = requiredEvidenceTools(questionPlan, context);
        var scope = scopes.create(username, context.documentVersionId(), assistantRunId);
        if (scope.isEmpty()) return deterministic;

        NativeToolAgent.RunResult result;
        if (!nativeAgent.supports(Role.ANSWER, username)) return deterministic;
        String providerId = nativeAgent.providerId(Role.ANSWER, username);
        RuleAnswerRateLimiter.Permit permit = rateLimiter.acquireModel(username, gameSessionId, providerId);
        try {
            RunRequest request = new RunRequest(
                    Role.ANSWER,
                    scope.get(),
                    SYSTEM_PROMPT,
                    playerRequest(question, context, questionPlan, deterministic.evidence()),
                    "EVIDENCE_REFINEMENT_UNAVAILABLE",
                    refinementBudget(questionPlan, context),
                    384,
                    toolPortfolio(question, context, questionPlan),
                    requiredEvidenceTools,
                    refinementToolBudget(questionPlan, context),
                    requiresTerminalCertification(questionPlan)
                            ? TerminalContract.evidenceReview()
                            : TerminalContract.none(),
                    requiresSourceAuthoredAdvice(questionPlan)
                            ? Map.of("read_rule_pages", 1)
                            : Map.of(),
                    !requiresCompleteListCertification(questionPlan));
            result = capture.enabled() ? nativeAgent.run(request, capture) : nativeAgent.run(request);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Answer evidence refinement failed for document version {}; preserving deterministic evidence",
                    context.documentVersionId());
            return deterministic;
        } finally {
            permit.close();
        }
        List<Set<UUID>> exactPageGroups = NativeToolEvidenceHandles.exactPageObservationGroups(
                result, 8, MAX_OBSERVED_EVIDENCE);
        LOGGER.debug(
                "Answer evidence refinement result: status={}, reason={}, toolCalls={}, exactPageGroups={}, observedHandles={}",
                result.status(),
                result.reason(),
                result.toolCalls(),
                exactPageGroups.size(),
                exactPageGroups.stream().mapToInt(Set::size).sum());
        if (!requiredEvidenceTools.isEmpty() && exactPageGroups.isEmpty()) {
            boolean certificationRequired = usesPriorPages(questionPlan, context)
                    || requiresSourceAuthoredAdvice(questionPlan)
                    || requiresCompleteListCertification(questionPlan)
                    || requiresNumericalScopeAudit(questionPlan);
            LOGGER.info(
                    "Answer evidence refinement did not complete its required exact-page confirmation; {} deterministic evidence",
                    certificationRequired ? "withholding" : "preserving");
            return certificationRequired
                    ? new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.READY)
                    : deterministic;
        }
        if (requiresSourceAuthoredAdvice(questionPlan)
                && (result.status() != RunStatus.COMPLETED
                        || result.terminalStatus() != TerminalStatus.EVIDENCE_READY)) {
            LOGGER.info(
                    "Answer evidence refinement did not certify source-authored advice; withholding candidate evidence");
            return new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.READY);
        }
        if (requiresCompleteListCertification(questionPlan)
                && (result.status() != RunStatus.COMPLETED
                        || result.terminalStatus() != TerminalStatus.EVIDENCE_READY)) {
            LOGGER.info(
                    "Answer evidence refinement did not certify complete-list coverage; withholding candidate evidence");
            return new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.READY);
        }
        // An exact-page observation is canonical application evidence even if a non-certifying Agent spends its
        // remaining turn on an unnecessary search and reaches its loop budget. Advice and complete-list plans have
        // already been held to their explicit terminal certification above; ordinary rule questions must not lose a
        // successfully read page merely because the optional acquisition loop failed to stop cleanly afterwards.
        boolean acquiredCanonicalPages = result.toolCalls() > 0 && !exactPageGroups.isEmpty();
        if (!acquiredCanonicalPages) return deterministic;
        Set<UUID> observedIds = exactPageGroups.stream()
                .flatMap(Set::stream)
                .limit(MAX_OBSERVED_EVIDENCE)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (observedIds.isEmpty()) return deterministic;
        return mergeCanonicalEvidence(
                context.documentVersionId(), deterministic, observedIds, exactPageGroups, questionPlan);
    }

    private Set<String> toolPortfolio(
            UnderstoodQuestion question, QuestionContext context, AnswerQuestionPlan questionPlan) {
        if (usesPriorPages(questionPlan, context)) return Set.of("read_rule_pages");
        Set<String> tools = new java.util.LinkedHashSet<>(Set.of(
                "search_rule_evidence", "expand_rule_evidence_context", "read_rule_pages"));
        Set<com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed> needs = questionPlan.evidenceNeeds();
        if (needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.VISUAL_REFERENCE)) {
            tools.add("read_visual_page_facts");
        }
        if (needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.RELATIONSHIP)
                || needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.EXCEPTION)) {
            tools.add("search_rule_relationships");
        }
        return Set.copyOf(tools);
    }

    private Set<String> requiredEvidenceTools(AnswerQuestionPlan questionPlan, QuestionContext context) {
        if (usesPriorPages(questionPlan, context)) return Set.of("read_rule_pages");
        // One exact-page observation can close one bounded obligation. A compound plan must return to the native
        // model after the first read so it can check the remaining independently planned obligations; otherwise a
        // page that answers only the first subquestion would prematurely end the entire evidence run.
        if (questionPlan.subquestions().size() == 1
                && (!questionPlan.agentPlanned()
                        || requiresSourceAuthoredAdvice(questionPlan)
                        || requiresNumericalScopeAudit(questionPlan)
                        || questionPlan.evidenceNeeds().contains(
                                com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.COMPLETE_LIST))) {
            return Set.of("read_rule_pages");
        }
        return Set.of();
    }

    private int refinementBudget(AnswerQuestionPlan questionPlan, QuestionContext context) {
        if (usesPriorPages(questionPlan, context)) return 2;
        if (questionPlan.subquestions().size() > 1) return 5;
        if (requiresCompleteListCertification(questionPlan)) return 5;
        if (!requiredEvidenceTools(questionPlan, context).isEmpty()) return 4;
        return 3;
    }

    private int refinementToolBudget(AnswerQuestionPlan questionPlan, QuestionContext context) {
        if (usesPriorPages(questionPlan, context)) return 1;
        if (questionPlan.subquestions().size() > 1) return 5;
        if (requiresSourceAuthoredAdvice(questionPlan)) return 4;
        if (requiresCompleteListCertification(questionPlan)) return 4;
        return 3;
    }

    private boolean requiresSourceAuthoredAdvice(AnswerQuestionPlan questionPlan) {
        return questionPlan.evidenceNeeds().contains(
                com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.ADVICE);
    }

    private boolean requiresCompleteListCertification(AnswerQuestionPlan questionPlan) {
        return questionPlan.evidenceNeeds().contains(
                com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.COMPLETE_LIST);
    }

    private boolean requiresTerminalCertification(AnswerQuestionPlan questionPlan) {
        return requiresSourceAuthoredAdvice(questionPlan) || requiresCompleteListCertification(questionPlan);
    }

    private boolean requiresNumericalScopeAudit(AnswerQuestionPlan questionPlan) {
        return questionPlan.answerAid() == com.rulepilot.assistant.RuleAnswerModel.AnswerAid.CALCULATION;
    }

    private AnswerEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            AnswerEvidenceRetriever.Result deterministic,
            Set<UUID> observedIds,
            List<Set<UUID>> exactPageObservationGroups,
            AnswerQuestionPlan questionPlan) {
        List<RuleEvidenceHit> hydrated;
        try {
            hydrated = evidenceLookup.findByChunkIds(documentVersionId, observedIds);
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn(
                    "Observed answer evidence could not be hydrated for document version {}; preserving deterministic evidence",
                    documentVersionId);
            return deterministic;
        }
        LOGGER.debug(
                "Answer exact-page evidence hydration: requestedHandles={}, hydratedHandles={}",
                observedIds.size(),
                hydrated.size());
        Map<UUID, RuleEvidenceHit> hydratedById = hydrated.stream()
                .filter(source -> documentVersionId.equals(source.documentVersionId()))
                .filter(source -> observedIds.contains(source.chunkId()))
                .collect(java.util.stream.Collectors.toMap(
                        RuleEvidenceHit::chunkId,
                        source -> source,
                        (first, duplicate) -> first));
        Map<UUID, HybridEvidenceHit> merged = new LinkedHashMap<>();
        deterministic.evidence().forEach(hit -> merged.put(hit.evidence().chunkId(), hit));
        List<HybridEvidenceHit> observed = new ArrayList<>();
        for (UUID observedId : observedIds) {
            RuleEvidenceHit source = hydratedById.get(observedId);
            if (source == null) continue;
            HybridEvidenceHit existing = merged.get(source.chunkId());
            HybridEvidenceHit canonical = new HybridEvidenceHit(source, source.score(), 1, null, false);
            if (existing != null && !sameCanonicalIdentity(existing.evidence(), source)) {
                return new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.CONFLICTING);
            }
            HybridEvidenceHit candidate = existing != null
                            && AnswerEvidencePolicy.isVisualPlaceholder(canonical)
                            && !AnswerEvidencePolicy.isVisualPlaceholder(existing)
                    ? existing
                    : canonical;
            merged.put(source.chunkId(), candidate);
            observed.add(candidate);
        }
        if (observed.isEmpty()) return deterministic;
        List<List<HybridEvidenceHit>> confirmedPageGroups = canonicalPageGroups(
                exactPageObservationGroups, hydratedById, merged);
        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                merged,
                observed,
                Set.of(),
                AnswerRetrievalInputMapper.plan(questionPlan),
                confirmedPageGroups);
        return new AnswerEvidenceRetriever.Result(selected, AnswerEvidenceRetriever.State.READY);
    }

    private static boolean sameCanonicalIdentity(RuleEvidenceHit existing, RuleEvidenceHit canonical) {
        return existing.chunkId().equals(canonical.chunkId())
                && existing.documentVersionId().equals(canonical.documentVersionId())
                && existing.sectionType().equals(canonical.sectionType())
                && existing.heading().equals(canonical.heading())
                && existing.pageFrom() == canonical.pageFrom()
                && existing.pageTo() == canonical.pageTo();
    }

    private List<List<HybridEvidenceHit>> canonicalPageGroups(
            List<Set<UUID>> observationGroups,
            Map<UUID, RuleEvidenceHit> hydratedById,
            Map<UUID, HybridEvidenceHit> evidenceById) {
        List<List<HybridEvidenceHit>> groups = new ArrayList<>();
        for (Set<UUID> observationGroup : observationGroups) {
            Map<PageRange, List<HybridEvidenceHit>> byCanonicalPage = new LinkedHashMap<>();
            for (UUID evidenceId : observationGroup) {
                RuleEvidenceHit canonical = hydratedById.get(evidenceId);
                HybridEvidenceHit hit = evidenceById.get(evidenceId);
                if (canonical == null || hit == null) continue;
                byCanonicalPage.computeIfAbsent(
                                new PageRange(canonical.pageFrom(), canonical.pageTo()), ignored -> new ArrayList<>())
                        .add(hit);
            }
            groups.addAll(byCanonicalPage.values().stream().filter(group -> !group.isEmpty()).toList());
        }
        return List.copyOf(groups);
    }

    private record PageRange(int from, int to) {}

    private String playerRequest(
            UnderstoodQuestion question,
            QuestionContext context,
            AnswerQuestionPlan questionPlan,
            List<HybridEvidenceHit> evidence) {
        StringBuilder request = new StringBuilder("Current player question (authoritative): ")
                .append(complete(question.originalQuestion()));
        if (questionPlan.boundReferenceQuestion() != null) {
            request.append("\nSelected reference question (reference resolution only): ")
                    .append(complete(questionPlan.boundReferenceQuestion()));
        }
        if (questionPlan.referenceBinding() == ReferenceBinding.PRIOR_GROUNDED_TURN
                && context.priorTurnReference() != null) {
            request.append("\nPrior grounded reference hint (not current evidence): ")
                    .append(complete(context.priorTurnReference().groundedVerdict()));
            for (var citation : context.priorTurnReference().citations()) {
                request.append("\n- prior citation handle ")
                        .append(citation.chunkId())
                        .append(" | pages ")
                        .append(citation.pageFrom())
                        .append('-')
                        .append(citation.pageTo());
            }
            request.append("\nResolve the player's reference, then re-read canonical current-version evidence before declaring ready.");
            List<Integer> priorPages = priorPages(context);
            if (usesPriorPages(questionPlan, context)) {
                request.append("\nPrior cited pages to re-read: ")
                        .append(priorPages)
                        .append(". Call read_rule_pages once with exactly these pageNumbers; do not search.");
            }
        }
        request.append("\nCurrent verified retrieval:");
        if (evidence.isEmpty()) request.append(" none");
        for (HybridEvidenceHit hit : evidence.stream().limit(5).toList()) {
            request.append("\n- ")
                    .append(hit.evidence().chunkId())
                    .append(" | ")
                    .append(complete(hit.evidence().heading()))
                    .append(" | pages ")
                    .append(hit.evidence().pageFrom())
                    .append('-')
                    .append(hit.evidence().pageTo())
                    .append(" | ")
                    .append(complete(hit.evidence().excerpt()));
        }
        request.append("\nAgent-validated question plan:");
        for (AnswerQuestionPlan.Subquestion subquestion : questionPlan.subquestions()) {
            request.append("\n- exact span: ")
                    .append(complete(subquestion.text()))
                    .append(" | owner: ")
                    .append(subquestion.owner())
                    .append(" | evidence needs: ")
                    .append(subquestion.evidenceNeeds());
        }
        if (!questionPlan.currentRuleObjectSpans().isEmpty()) {
            request.append("\nCurrent-question rule objects that must not be substituted: ")
                    .append(questionPlan.currentRuleObjectSpans());
        }
        if (!questionPlan.pageHints().isEmpty()) {
            request.append("\nPlayer page locators (hints only, not claims): ")
                    .append(questionPlan.pageHints().stream()
                            .map(AnswerQuestionPlan.PageHint::pageNumber)
                            .toList());
        }
        request.append("\nUse observations to cover every listed span. Stop after the useful exact pages have been read; "
                + "the application ignores terminal prose and admits only canonical page observations.");
        return request.toString();
    }

    private String complete(String value) {
        return value == null ? "" : value.strip();
    }

    private boolean usesPriorPages(AnswerQuestionPlan questionPlan, QuestionContext context) {
        return questionPlan != null
                && questionPlan.referenceBinding()
                        == com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding.PRIOR_GROUNDED_TURN
                && !priorPages(context).isEmpty();
    }

    private List<Integer> priorPages(QuestionContext context) {
        if (context == null || context.priorTurnReference() == null) return List.of();
        return context.priorTurnReference().citations().stream()
                .flatMapToInt(citation -> java.util.stream.IntStream.rangeClosed(
                        citation.pageFrom(),
                        (int) Math.min((long) citation.pageTo(), (long) citation.pageFrom() + 4)))
                .distinct()
                .limit(5)
                .boxed()
                .toList();
    }
}
