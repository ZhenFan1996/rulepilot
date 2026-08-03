package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolEvidenceHandles;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Acquires missing chapter evidence through native read tools without composing or publishing lesson prose. */
@Service
@Profile("!test")
public class TeachingEvidenceAgent implements TeachingEvidenceRefiner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingEvidenceAgent.class);
    private static final int MAX_EVIDENCE_PER_SECTION = 10;
    private static final int MAX_OBSERVED_EVIDENCE = 24;
    private static final String SYSTEM_PROMPT = """
            You refine evidence for one board-game teaching chapter. Never write the chapter and never use rule
            knowledge outside supplied evidence and tool observations. Use the read-only rulebook tools only when a
            validated chapter objective, coverage tag, or source-page coordinate remains unsupported. Search one
            missing teaching need at a time. Read exact pages when a search result or validated source-page hint needs
            confirmation. Return exactly EVIDENCE_READY when the chapter evidence is sufficient. Never change scope,
            invent a page, or treat your own prose as evidence.
            """;

    private final NativeToolAgent nativeAgent;
    private final NativeToolScopes scopes;
    private final AssistantReadTools tools;
    private final EvidenceVerifier evidenceVerifier;

    public TeachingEvidenceAgent(
            NativeToolAgent nativeAgent,
            NativeToolScopes scopes,
            AssistantReadTools tools,
            EvidenceVerifier evidenceVerifier) {
        this.nativeAgent = nativeAgent;
        this.scopes = scopes;
        this.tools = tools;
        this.evidenceVerifier = evidenceVerifier;
    }

    @Override
    public TeachingSectionEvidenceRetriever.Result refine(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result deterministic) {
        if (!TeachingEvidenceRefinementPolicy.requiresRefinement(planned, deterministic)) return deterministic;
        var scope = scopes.create(plan.createdBy(), plan.documentVersionId(), assistantRunId);
        if (scope.isEmpty()) return deterministic;

        NativeToolAgent.RunResult result;
        if (!nativeAgent.supports(Role.TEACHING, plan.createdBy())) return deterministic;
        try {
            result = nativeAgent.run(new RunRequest(
                    Role.TEACHING,
                    scope.get(),
                    SYSTEM_PROMPT,
                    chapterRequest(planned, deterministic.evidence()),
                    "EVIDENCE_REFINEMENT_UNAVAILABLE",
                    4,
                    384));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Teaching evidence refinement failed for section {}; preserving deterministic evidence",
                    planned.position());
            return deterministic;
        }
        int totalToolCalls = deterministic.toolCalls() + result.toolCalls();
        if (result.status() != RunStatus.COMPLETED || result.toolCalls() == 0) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        Set<UUID> observedIds = NativeToolEvidenceHandles.prioritized(result, MAX_OBSERVED_EVIDENCE);
        if (observedIds.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        return mergeCanonicalEvidence(plan.documentVersionId(), deterministic, observedIds, totalToolCalls);
    }

    private TeachingSectionEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            TeachingSectionEvidenceRetriever.Result deterministic,
            Set<UUID> observedIds,
            int totalToolCalls) {
        List<RuleEvidence> hydrated;
        try {
            hydrated = tools.readRuleEvidenceIds(documentVersionId, observedIds);
        } catch (RuntimeException lookupFailure) {
            LOGGER.warn("Observed teaching evidence could not be hydrated; preserving deterministic evidence");
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        Map<UUID, RuleEvidence> hydratedById = hydrated.stream()
                .filter(source -> documentVersionId.equals(source.documentVersionId()))
                .filter(source -> observedIds.contains(source.chunkId()))
                .collect(java.util.stream.Collectors.toMap(
                        RuleEvidence::chunkId,
                        source -> source,
                        (first, duplicate) -> first));
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        deterministic.evidence().forEach(source -> merged.put(source.chunkId(), source));
        List<RuleEvidence> prioritized = new ArrayList<>();
        for (UUID observedId : observedIds) {
            RuleEvidence source = hydratedById.get(observedId);
            if (source == null) continue;
            RuleEvidence existing = merged.get(observedId);
            if (existing != null && !sameEvidence(existing, source)) {
                return new TeachingSectionEvidenceRetriever.Result(
                        List.of(), totalToolCalls, TeachingSectionEvidenceRetriever.State.INVALID);
            }
            merged.put(observedId, source);
            prioritized.add(source);
        }
        if (prioritized.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        deterministic.evidence().stream()
                .filter(source -> !observedIds.contains(source.chunkId()))
                .forEach(prioritized::add);
        List<RuleEvidence> selected = prioritized.stream().limit(MAX_EVIDENCE_PER_SECTION).toList();
        boolean verified = evidenceVerifier.verify(new VerificationRequest(
                        documentVersionId,
                        selected.stream().map(this::verifierEvidence).toList(),
                        List.of()))
                .verified();
        return verified
                ? new TeachingSectionEvidenceRetriever.Result(
                        selected, totalToolCalls, TeachingSectionEvidenceRetriever.State.VERIFIED)
                : new TeachingSectionEvidenceRetriever.Result(
                        List.of(), totalToolCalls, TeachingSectionEvidenceRetriever.State.INVALID);
    }

    private String chapterRequest(TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence) {
        StringBuilder request = new StringBuilder("Chapter title: ")
                .append(bounded(planned.title(), 240))
                .append("\nObjective: ")
                .append(bounded(planned.objective(), 700))
                .append("\nCoverage tags: ")
                .append(bounded(String.join(", ", planned.coverageTags()), 400))
                .append("\nValidated source pages: ")
                .append(planned.sourcePageNumbers())
                .append("\nCurrent verified evidence:");
        if (evidence.isEmpty()) request.append(" none");
        for (RuleEvidence source : evidence.stream().limit(8).toList()) {
            request.append("\n- ")
                    .append(source.chunkId())
                    .append(" | ")
                    .append(bounded(source.heading(), 160))
                    .append(" | pages ")
                    .append(source.pageFrom())
                    .append('-')
                    .append(source.pageTo())
                    .append(" | ")
                    .append(bounded(source.excerpt(), 500));
        }
        return request.toString();
    }

    private boolean sameEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.excerpt().equals(second.excerpt())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo();
    }

    private EvidenceSource verifierEvidence(RuleEvidence source) {
        return new EvidenceSource(
                source.chunkId(),
                source.documentVersionId(),
                source.sectionType(),
                source.excerpt(),
                source.pageFrom(),
                source.pageTo());
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
