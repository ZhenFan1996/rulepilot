package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import java.util.List;
import java.util.UUID;

/** Bounded answer-model calls with one permit lifecycle and the player-visible audit activity. */
final class AnswerModelGateway {

    private final RuleAnswerModel model;
    private final RuleAnswerRateLimiter rateLimiter;
    private final AuditedAgentInvocations invocations;

    AnswerModelGateway(
            RuleAnswerModel model, RuleAnswerRateLimiter rateLimiter, AuditedAgentInvocations invocations) {
        this.model = model;
        this.rateLimiter = rateLimiter;
        this.invocations = invocations;
    }

    ModelDraft compose(UUID runId, String username, UUID gameSessionId, ModelRequest request) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, gameSessionId);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    "composeRuleAnswer",
                    estimateTokens(request.toString()),
                    "Rule answer model output received",
                    () -> model.compose(request),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    ModelDraft revise(
            UUID runId,
            String username,
            UUID gameSessionId,
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            String operation,
            String successSummary) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, gameSessionId);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    operation,
                    estimateTokens(request.toString()) + estimateTokens(feedback.toString()),
                    successSummary,
                    () -> model.revise(request, previousDraft, feedback),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    List<String> rewriteRetrievalQueries(
            UUID runId, String username, RetrievalQueryRequest request) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, null);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    "rewriteAnswerRetrievalQueries",
                    estimateTokens(request.question()),
                    "Cross-language retrieval phrases prepared",
                    () -> model.rewriteRetrievalQueries(request),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    private RuleAnswerRateLimiter.Permit acquire(String username, UUID gameSessionId) {
        return rateLimiter.acquireModel(username, gameSessionId, model.providerId());
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
