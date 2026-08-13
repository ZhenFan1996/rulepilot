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

/** Fills validated planned-page gaps through one scope-bound, audited canonical read. */
@Service
@Profile("!test")
public class TeachingSourcePageEvidenceRefiner implements TeachingEvidenceRefiner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingSourcePageEvidenceRefiner.class);
    private static final int MAX_EVIDENCE_PER_SECTION = 10;
    private static final int MAX_EVIDENCE_CHUNKS_PER_PAGE = 3;
    private static final int MAX_OBSERVED_EVIDENCE = 24;

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
        List<Integer> missingSourcePages = missingSourcePages(planned, deterministic.evidence());
        if (missingSourcePages.isEmpty()) return deterministic;

        List<RuleEvidence> observed;
        try {
            observed = invocations.invoke(
                    assistantRunId,
                    ActivityType.TOOL,
                    operationName(planned.position()),
                    missingSourcePages.size(),
                    "Validated teaching source pages read",
                    () -> tools.readRuleEvidencePages(
                                    plan.documentVersionId(), Set.copyOf(missingSourcePages), false)
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
                deterministic,
                observed,
                Set.copyOf(missingSourcePages),
                deterministic.toolCalls() + 1);
    }

    private TeachingSectionEvidenceRetriever.Result mergeCanonicalEvidence(
            UUID documentVersionId,
            TeachingSectionEvidenceRetriever.Result deterministic,
            List<RuleEvidence> observed,
            Set<Integer> allowedPages,
            int totalToolCalls) {
        Map<UUID, RuleEvidence> merged = new LinkedHashMap<>();
        deterministic.evidence().forEach(source -> merged.put(source.chunkId(), source));
        List<RuleEvidence> prioritized = new ArrayList<>();
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
            prioritized.add(source);
        }
        if (prioritized.isEmpty()) {
            return new TeachingSectionEvidenceRetriever.Result(
                    deterministic.evidence(), totalToolCalls, deterministic.state());
        }
        Set<UUID> observedIds = prioritized.stream()
                .map(RuleEvidence::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        deterministic.evidence().stream()
                .filter(source -> !observedIds.contains(source.chunkId()))
                .forEach(prioritized::add);
        List<RuleEvidence> selected = selectPageDiverseEvidence(prioritized);
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

    private List<RuleEvidence> selectPageDiverseEvidence(List<RuleEvidence> prioritized) {
        Map<String, Integer> chunksPerPage = new LinkedHashMap<>();
        List<RuleEvidence> selected = new ArrayList<>();
        for (RuleEvidence source : prioritized) {
            String pageKey = source.pageFrom() + ":" + source.pageTo();
            int pageCount = chunksPerPage.getOrDefault(pageKey, 0);
            if (pageCount >= MAX_EVIDENCE_CHUNKS_PER_PAGE) continue;
            selected.add(source);
            chunksPerPage.put(pageKey, pageCount + 1);
            if (selected.size() == MAX_EVIDENCE_PER_SECTION) break;
        }
        return List.copyOf(selected);
    }

    private List<Integer> missingSourcePages(
            TeachingPlan.PlannedSection planned, List<RuleEvidence> evidence) {
        Set<Integer> evidencedPages = evidence.stream()
                .flatMap(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo()).boxed())
                .collect(java.util.stream.Collectors.toSet());
        return planned.sourcePageNumbers().stream()
                .filter(page -> !evidencedPages.contains(page))
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
