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
            run deterministic retrieval. Use the read-only rulebook tools only to fill an uncovered condition,
            exception, list item, follow-up dependency, or empty-result gap. Search one bounded need at a time. Read
            exact pages after search to confirm the passages that cover the missing need. For a compound question,
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
        if (!AnswerEvidenceRefinementPolicy.requiresRefinement(question, context, deterministic)) {
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
                    playerRequest(question, context, deterministic.evidence()),
                    "EVIDENCE_REFINEMENT_UNAVAILABLE",
                    4,
                    384,
                    Set.of("read_rule_pages")));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Answer evidence refinement failed for document version {}; preserving deterministic evidence",
                    context.documentVersionId());
            return deterministic;
        } finally {
            permit.close();
        }
        if (result.status() != RunStatus.COMPLETED || result.toolCalls() == 0) return deterministic;
        Set<UUID> observedIds = NativeToolEvidenceHandles.prioritized(result, MAX_OBSERVED_EVIDENCE);
        if (observedIds.isEmpty()) return deterministic;
        List<Set<UUID>> exactPageGroups = NativeToolEvidenceHandles.exactPageObservationGroups(
                result, 8, MAX_OBSERVED_EVIDENCE);
        return mergeCanonicalEvidence(
                context.documentVersionId(), question.normalizedQuestion(), deterministic, observedIds, exactPageGroups);
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
            HybridEvidenceHit candidate = new HybridEvidenceHit(source, source.score(), 1, null, false);
            HybridEvidenceHit existing = merged.get(source.chunkId());
            if (existing != null && !AnswerEvidencePolicy.sameEvidenceSnapshot(existing, candidate)) {
                return new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.CONFLICTING);
            }
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
            UnderstoodQuestion question, QuestionContext context, List<HybridEvidenceHit> evidence) {
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
        return request.toString();
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
