package com.rulepilot.teaching.application;

import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.SearchRuleEvidence;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.EvidenceVerifier.VerificationRequest;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retrieves, reconciles, and verifies one chapter's source evidence before draft composition. */
final class TeachingSectionEvidenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(TeachingSectionEvidenceRetriever.class);
    private static final int EVIDENCE_PER_INTENT = 3;

    private final AssistantReadTools tools;
    private final EvidenceVerifier evidenceVerifier;
    private final AuditedAgentInvocations invocations;
    private final TeachingVisualEvidenceResolver visualEvidenceResolver;

    TeachingSectionEvidenceRetriever(
            AssistantReadTools tools,
            EvidenceVerifier evidenceVerifier,
            AuditedAgentInvocations invocations,
            TeachingVisualEvidenceResolver visualEvidenceResolver) {
        this.tools = tools;
        this.evidenceVerifier = evidenceVerifier;
        this.invocations = invocations;
        this.visualEvidenceResolver = visualEvidenceResolver;
    }

    Result retrieve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            int queryBudget,
            boolean bindVisualPageEvidence) {
        return retrieve(
                plan,
                planned,
                assistantRunId,
                queryBudget,
                bindVisualPageEvidence,
                CaptureHandle.noop());
    }

    Result retrieve(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            UUID assistantRunId,
            int queryBudget,
            boolean bindVisualPageEvidence,
            CaptureHandle capture) {
        if (bindVisualPageEvidence && ProgressiveVisualTeachingPlanPolicy.isProgressive(plan)) {
            try {
                List<RuleEvidence> evidence = visualEvidenceResolver.resolve(
                        plan, planned, List.of(), assistantRunId, capture);
                return verifiedResult(plan, evidence, 1);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException visualFailure) {
                log.warn(
                        "Teaching visual evidence resolution failed for section {} (failureType={})",
                        planned.position(),
                        visualFailure.getClass().getSimpleName());
                return new Result(List.of(), 1, State.EMPTY);
            }
        }
        Map<UUID, RuleEvidence> evidenceById = new LinkedHashMap<>();
        List<List<RuleEvidence>> evidenceByIntent = new ArrayList<>();
        boolean conflictingEvidence = false;
        int toolCalls = 0;
        for (String query : TeachingEvidenceRetrievalPolicy.queries(planned, queryBudget)) {
            toolCalls++;
            try {
                List<RuleEvidence> retrieved = invocations.invoke(
                        assistantRunId,
                        ActivityType.TOOL,
                        operationName("searchRuleEvidence", planned.position()),
                        estimateTokens(query),
                        "Version-scoped rule evidence retrieved",
                        () -> retrieve(plan.documentVersionId(), planned.topicKey(), query),
                        this::evidenceTokens);
                evidenceByIntent.add(retrieved);
                for (RuleEvidence source : retrieved) {
                    RuleEvidence existing = evidenceById.putIfAbsent(source.chunkId(), source);
                    if (existing != null && !sameEvidence(existing, source)) {
                        conflictingEvidence = true;
                        break;
                    }
                }
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException retrievalFailure) {
                log.warn(
                        "Teaching evidence retrieval failed for section {} (failureType={})",
                        planned.position(),
                        retrievalFailure.getClass().getSimpleName());
            }
            if (conflictingEvidence) break;
        }
        List<RuleEvidence> evidence = conflictingEvidence
                ? List.of()
                : TeachingEvidenceRetrievalPolicy.balancedEvidence(evidenceByIntent);
        if (bindVisualPageEvidence) {
            try {
                evidence = visualEvidenceResolver.resolve(plan, planned, evidence, assistantRunId, capture);
            } catch (AgentExecutionStoppedException stopped) {
                throw stopped;
            } catch (RuntimeException visualFailure) {
                log.warn(
                        "Optional visual evidence resolution failed for section {} (failureType={}); retaining text evidence",
                        planned.position(),
                        visualFailure.getClass().getSimpleName());
            }
        }
        return verifiedResult(plan, evidence, toolCalls);
    }

    private Result verifiedResult(TeachingPlan plan, List<RuleEvidence> evidence, int toolCalls) {
        if (evidence.isEmpty()) return new Result(List.of(), toolCalls, State.EMPTY);
        boolean verified;
        try {
            verified = evidenceVerifier.verify(new VerificationRequest(
                            plan.documentVersionId(), evidence.stream().map(this::toVerifierEvidence).toList(), List.of()))
                    .verified();
        } catch (RuntimeException verificationFailure) {
            log.warn(
                    "Teaching evidence verification failed (failureType={})",
                    verificationFailure.getClass().getSimpleName());
            verified = false;
        }
        return verified
                ? new Result(evidence, toolCalls, State.VERIFIED)
                : new Result(List.of(), toolCalls, State.INVALID);
    }

    void prefetchRemainingVisualFacts(
            TeachingPlan plan,
            int completedSections,
            UUID assistantRunId) {
        prefetchRemainingVisualFacts(
                plan,
                completedSections,
                assistantRunId,
                CaptureHandle.noop());
    }

    void prefetchRemainingVisualFacts(
            TeachingPlan plan,
            int completedSections,
            UUID assistantRunId,
            CaptureHandle capture) {
        visualEvidenceResolver.prefetchRemaining(plan, completedSections, assistantRunId, capture);
    }

    private List<RuleEvidence> retrieve(UUID documentVersionId, String topicKey, String query) {
        return List.copyOf(tools.searchRuleEvidence(new SearchRuleEvidence(
                documentVersionId,
                TeachingEvidenceRetrievalPolicy.focusedQuery(query),
                EVIDENCE_PER_INTENT,
                Set.of(),
                null,
                true,
                false)));
    }

    private boolean sameEvidence(RuleEvidence first, RuleEvidence second) {
        return first.chunkId().equals(second.chunkId())
                && first.documentVersionId().equals(second.documentVersionId())
                && first.sectionType().equals(second.sectionType())
                && first.heading().equals(second.heading())
                && first.pageFrom() == second.pageFrom()
                && first.pageTo() == second.pageTo();
    }

    private EvidenceSource toVerifierEvidence(RuleEvidence evidence) {
        return new EvidenceSource(
                evidence.chunkId(),
                evidence.documentVersionId(),
                evidence.sectionType(),
                evidence.excerpt(),
                evidence.pageFrom(),
                evidence.pageTo());
    }

    private int evidenceTokens(List<RuleEvidence> evidence) {
        return evidence.stream().mapToInt(source -> estimateTokens(source.excerpt())).sum();
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private String operationName(String operation, int sectionPosition) {
        return operation + "|" + sectionPosition;
    }

    enum State { VERIFIED, EMPTY, INVALID }

    record Result(List<RuleEvidence> evidence, int toolCalls, State state) {
        boolean verified() {
            return state == State.VERIFIED;
        }
    }
}
