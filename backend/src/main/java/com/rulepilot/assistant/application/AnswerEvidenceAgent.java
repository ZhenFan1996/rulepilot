package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolEvidenceHandles;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private static final Set<String> RECOVERABLE_PARTIAL_RUN_REASONS = Set.of(
            "EMPTY_MODEL_RESULT",
            "COMPLETION_PROTOCOL_REJECTED",
            "ITERATION_LIMIT",
            "TOOL_CALL_LIMIT");
    private static final Pattern PRIOR_TURN_REFERENCE = Pattern.compile(
            "(?iu)\\b(?:this|that|it|these|those|then|there|former|latter|such)\\b|"
                    + "这个|那个|这样|那样|它|上述|前述|刚才|上面|这里|那里");
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
            missing need. When the player asks for an example, search for the player's topic together with neutral
            source cues such as worked example, for example, 示例, or 例如. A cue is only a retrieval hint: acquire the
            complete cited setup, action, and outcome, and do not turn that example into a general rule. For a compound question,
            check every requested condition, sequence, exception, and complete-list obligation separately. A result
            count does not prove coverage: if a broad search misses one obligation, search that obligation again with
            the player's distinctive wording before reading the best candidate page. Only after every obligation has
            a confirmed page observation may you return exactly EVIDENCE_READY. Do not invent identifiers or scope.
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
        if (!AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, questionPlan, deterministic)) {
            return deterministic;
        }
        var scope = scopes.create(username, context.documentVersionId(), assistantRunId);
        if (scope.isEmpty()) return deterministic;

        NativeToolAgent.RunResult result;
        if (!nativeAgent.supports(Role.ANSWER, username)) return deterministic;
        String providerId = nativeAgent.providerId(Role.ANSWER, username);
        RuleAnswerRateLimiter.Permit permit = rateLimiter.acquireModel(username, gameSessionId, providerId);
        try {
            result = nativeAgent.run(new RunRequest(
                    Role.ANSWER,
                    scope.get(),
                    SYSTEM_PROMPT,
                    playerRequest(question, context, questionPlan, deterministic.evidence()),
                    "EVIDENCE_REFINEMENT_UNAVAILABLE",
                    usesPriorPages(question, context)
                            ? 2
                            : questionPlan.subquestions().size() > 1 ? 5 : 4,
                    384,
                    toolPortfolio(question, context, questionPlan),
                    requiredEvidenceTools(question, context),
                    usesPriorPages(question, context) ? 1 : questionPlan.subquestions().size() > 1 ? 5 : 4,
                    "EVIDENCE_READY"));
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
        boolean declaredReady = result.status() == RunStatus.COMPLETED
                && "EVIDENCE_READY".equals(result.text().strip())
                && result.toolCalls() > 0;
        boolean recoverablePartialRun = result.status() == RunStatus.FALLBACK
                && RECOVERABLE_PARTIAL_RUN_REASONS.contains(result.reason())
                && result.toolCalls() > 0
                && !exactPageGroups.isEmpty();
        if (!declaredReady && !recoverablePartialRun) return deterministic;
        if (recoverablePartialRun) {
            // The model already chose and completed exact-page reads; a later empty turn or
            // budget edge must not erase canonical evidence. This does not accept model prose
            // or declare coverage complete—the normal evidence selector and publication gates
            // still decide whether the observed source can support the player's answer.
            LOGGER.info(
                    "Preserving validated exact-page observations from a partial Answer Agent run ({})",
                    result.reason());
        }
        Set<UUID> observedIds = exactPageGroups.stream()
                .flatMap(Set::stream)
                .limit(MAX_OBSERVED_EVIDENCE)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (observedIds.isEmpty()) return deterministic;
        return mergeCanonicalEvidence(
                context.documentVersionId(), question.normalizedQuestion(), deterministic, observedIds, exactPageGroups);
    }

    private Set<String> toolPortfolio(
            UnderstoodQuestion question, QuestionContext context, AnswerQuestionPlan questionPlan) {
        if (usesPriorPages(question, context)) return Set.of("read_rule_pages");
        Set<String> tools = new java.util.LinkedHashSet<>(Set.of(
                "search_rule_evidence", "expand_rule_evidence_context", "read_rule_pages"));
        Set<com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed> needs = questionPlan.evidenceNeeds();
        if (needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.VISUAL_REFERENCE)
                || (!questionPlan.agentPlanned()
                        && AnswerEvidenceRefinementPolicy.asksAboutVisualReference(question.normalizedQuestion()))) {
            tools.add("read_visual_page_facts");
        }
        if (needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.RELATIONSHIP)
                || needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.EXCEPTION)
                || (!questionPlan.agentPlanned()
                        && AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship(question.normalizedQuestion()))) {
            tools.add("search_rule_relationships");
        }
        return Set.copyOf(tools);
    }

    private Set<String> requiredEvidenceTools(UnderstoodQuestion question, QuestionContext context) {
        if (usesPriorPages(question, context)) return Set.of("read_rule_pages");
        return Set.of();
    }

    private AnswerEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            String normalizedQuestion,
            AnswerEvidenceRetriever.Result deterministic,
            Set<UUID> observedIds,
            List<Set<UUID>> exactPageObservationGroups) {
        List<RuleEvidenceHit> hydrated;
        try {
            hydrated = evidenceLookup.findByChunkIds(documentVersionId, observedIds);
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn(
                    "Observed answer evidence could not be hydrated for document version {}; preserving deterministic evidence",
                    documentVersionId);
            return deterministic;
        }
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
                normalizedQuestion, merged, observed, Set.of(), confirmedPageGroups);
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
        StringBuilder request = new StringBuilder("Player question: ")
                .append(bounded(question.normalizedQuestion(), 800));
        if (context.previousQuestion() != null && !context.previousQuestion().isBlank()) {
            request.append("\nPrevious question: ").append(bounded(context.previousQuestion(), 500));
        }
        if (context.priorTurnReference() != null) {
            request.append("\nPrior grounded reference hint (not current evidence): ")
                    .append(bounded(context.priorTurnReference().groundedVerdict(), 500));
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
            if (usesPriorPages(question, context)) {
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
                    .append(bounded(hit.evidence().heading(), 160))
                    .append(" | pages ")
                    .append(hit.evidence().pageFrom())
                    .append('-')
                    .append(hit.evidence().pageTo())
                    .append(" | ")
                    .append(bounded(hit.evidence().excerpt(), 600));
        }
        request.append("\nAgent-validated question plan:");
        for (AnswerQuestionPlan.Subquestion subquestion : questionPlan.subquestions()) {
            request.append("\n- exact span: ")
                    .append(bounded(subquestion.text(), 300))
                    .append(" | evidence needs: ")
                    .append(subquestion.evidenceNeeds());
        }
        request.append("\nUse observations to cover every listed span. EVIDENCE_READY is accepted only when exact pages have been read.");
        return request.toString();
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private boolean usesPriorPages(UnderstoodQuestion question, QuestionContext context) {
        return question != null
                && PRIOR_TURN_REFERENCE.matcher(question.normalizedQuestion()).find()
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
