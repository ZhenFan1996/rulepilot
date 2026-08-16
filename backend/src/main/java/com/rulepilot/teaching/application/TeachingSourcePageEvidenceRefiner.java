package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
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

/** Completes validated planned-page evidence through one scope-bound, audited canonical read. */
@Service
@Profile("!test")
public class TeachingSourcePageEvidenceRefiner implements TeachingEvidenceRefiner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingSourcePageEvidenceRefiner.class);
    private static final int MAX_EVIDENCE_PER_SECTION = 16;
    private static final int MAX_EVIDENCE_CHUNKS_PER_PAGE = 4;
    private static final int MAX_OBSERVED_EVIDENCE = 32;

    private final NativeToolScopes scopes;
    private final AssistantReadTools tools;
    private final EvidenceVerifier evidenceVerifier;
    private final AuditedAgentInvocations invocations;

    public TeachingSourcePageEvidenceRefiner(
            NativeToolScopes scopes,
            AssistantReadTools tools,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations) {
        this.scopes = scopes;
        this.tools = tools;
        this.evidenceVerifier = evidenceVerifier;
        this.invocations = invocations;
    }

    @Override
    public TeachingSectionEvidenceRetriever.Result refine(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            TeachingSectionEvidenceRetriever.Result deterministic) {
        if (ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)) return deterministic;
        if (!TeachingEvidenceRefinementPolicy.requiresRefinement(planned, deterministic)) return deterministic;
        if (scopes.create(plan.createdBy(), plan.documentVersionId(), assistantRunId).isEmpty()) return deterministic;
        List<Integer> plannedSourcePages = plannedSourcePages(planned);
        if (plannedSourcePages.isEmpty()) return deterministic;

        List<RuleEvidence> observed;
        try {
            observed = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    operationName(planned.position()),
                    plannedSourcePages.size(),
                    "Validated teaching source pages read",
                    () -> tools.readRuleEvidencePages(
                                    plan.documentVersionId(), Set.copyOf(plannedSourcePages), false)
                            .stream()
                            .limit(MAX_OBSERVED_EVIDENCE)
                            .toList(),
                    this::evidenceTokens);
        } catch (AgentExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Teaching source-page evidence read failed for section {}; preserving deterministic evidence",
                    planned.position());
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), deterministic.toolCalls() + 1, deterministic.state());
        }
        return mergeCanonicalEvidence(
                plan.documentVersionId(),
                planned,
                deterministic,
                observed,
                Set.copyOf(plannedSourcePages),
                deterministic.toolCalls() + 1);
    }

    private TeachingSectionEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            TeachingPlan.PlannedSection planned,
            TeachingSectionEvidenceRetriever.Result deterministic,
            List<RuleEvidence> observed,
            Set<Integer> allowedPages,
            int totalToolCalls) {
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        deterministic.evidence().forEach(source -> merged.put(source.chunkId(), source));
        List<RuleEvidence> canonicalExtras = new ArrayList<>();
        for (RuleEvidence source : observed) {
            if (!documentVersionId.equals(source.documentVersionId())) {
                return invalid(totalToolCalls);
            }
            boolean onAllowedPage = java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                    .anyMatch(allowedPages::contains);
            if (!onAllowedPage) continue;
            RuleEvidence existing = merged.get(source.chunkId());
            if (existing != null && !sameEvidence(existing, source)) {
                return invalid(totalToolCalls);
            }
            merged.put(source.chunkId(), source);
            if (existing == null) canonicalExtras.add(source);
        }
        if (observed.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        List<RuleEvidence> prioritized = new ArrayList<>(deterministic.evidence());
        prioritized.addAll(canonicalExtras);
        List<String> plannedSourceIdentifiers = TeachingUnitContract.decodeUnits(planned.retrievalQueries()).stream()
                .flatMap(unit -> unit.sourceIdentifiers().stream())
                .distinct()
                .toList();
        List<RuleEvidence> selected = selectSourceCompleteEvidence(prioritized, plannedSourceIdentifiers);
        if (!coversEverySourceIdentifier(selected, plannedSourceIdentifiers)) {
            return invalid(totalToolCalls);
        }
        boolean verified = evidenceVerifier.verify(new VerificationRequest(
                        documentVersionId,
                        selected.stream().map(this::verifierEvidence).toList(),
                        List.of()))
                .verified();
        return verified
                ? new TeachingSectionEvidenceRetriever.Result(
                        selected, totalToolCalls, TeachingSectionEvidenceRetriever.State.VERIFIED)
                : invalid(totalToolCalls);
    }

    private TeachingSectionEvidenceRetriever.Result invalid(int totalToolCalls) {
        return new TeachingSectionEvidenceRetriever.Result(
                List.of(), totalToolCalls, TeachingSectionEvidenceRetriever.State.INVALID);
    }

    private List<RuleEvidence> selectSourceCompleteEvidence(
            List<RuleEvidence> prioritized, List<String> sourceIdentifiers) {
        LinkedHashMap<UUID, RuleEvidence> selected = new LinkedHashMap<>();
        for (String identifier : sourceIdentifiers) {
            prioritized.stream()
                    .filter(source -> TeachingPlannedUnitCoveragePolicy.containsIdentifier(
                            source.heading() + " " + source.excerpt(), identifier))
                    .findFirst()
                    .ifPresent(source -> {
                        if (selected.size() < MAX_EVIDENCE_PER_SECTION) {
                            selected.putIfAbsent(source.chunkId(), source);
                        }
                    });
        }

        Map<String, List<RuleEvidence>> evidenceByPage = new LinkedHashMap<>();
        for (RuleEvidence source : prioritized) {
            String pageKey = source.pageFrom() + ":" + source.pageTo();
            List<RuleEvidence> pageEvidence = evidenceByPage.computeIfAbsent(pageKey, ignored -> new ArrayList<>());
            if (pageEvidence.stream().noneMatch(existing -> existing.chunkId().equals(source.chunkId()))) {
                pageEvidence.add(source);
            }
        }
        for (int rank = 0; rank < MAX_EVIDENCE_CHUNKS_PER_PAGE; rank++) {
            for (List<RuleEvidence> pageEvidence : evidenceByPage.values()) {
                if (rank >= pageEvidence.size()) continue;
                RuleEvidence source = pageEvidence.get(rank);
                selected.putIfAbsent(source.chunkId(), source);
                if (selected.size() == MAX_EVIDENCE_PER_SECTION) return List.copyOf(selected.values());
            }
        }
        return List.copyOf(selected.values());
    }

    private boolean coversEverySourceIdentifier(List<RuleEvidence> evidence, List<String> sourceIdentifiers) {
        return sourceIdentifiers.stream().allMatch(identifier -> evidence.stream().anyMatch(source ->
                TeachingPlannedUnitCoveragePolicy.containsIdentifier(
                        source.heading() + " " + source.excerpt(), identifier)));
    }

    private List<Integer> plannedSourcePages(TeachingPlan.PlannedSection planned) {
        return planned.sourcePageNumbers().stream()
                .distinct()
                .limit(5)
                .toList();
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

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(int sectionPosition) {
        return "readTeachingSourcePages|" + sectionPosition;
    }
}
