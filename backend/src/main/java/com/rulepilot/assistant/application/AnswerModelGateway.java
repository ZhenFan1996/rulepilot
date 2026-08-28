package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded answer-model calls with one permit lifecycle and the player-visible audit activity. */
final class AnswerModelGateway {

    private final RuleAnswerModel model;
    private final RuleAnswerRateLimiter rateLimiter;
    private final AuditedAgentInvocations invocations;
    private final AgentInvocationDeadline deadline;

    AnswerModelGateway(
            RuleAnswerModel model, RuleAnswerRateLimiter rateLimiter, AuditedAgentInvocations invocations) {
        this(model, rateLimiter, invocations, AgentInvocationDeadline.unbounded());
    }

    AnswerModelGateway(
            RuleAnswerModel model,
            RuleAnswerRateLimiter rateLimiter,
            AuditedAgentInvocations invocations,
            AgentInvocationDeadline deadline) {
        this.model = model;
        this.rateLimiter = rateLimiter;
        this.invocations = invocations;
        this.deadline = deadline;
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
                    () -> deadline.invoke(runId, () -> model.compose(request, username)),
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
                    () -> deadline.invoke(
                            runId, () -> model.revise(request, previousDraft, feedback, username)),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    boolean supportsQuestionInterpretation(String username) {
        return model.supportsQuestionInterpretation(username);
    }

    Optional<QuestionInterpretationDraft> interpretQuestion(
            UUID runId,
            String username,
            UUID gameSessionId,
            QuestionInterpretationRequest request) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, gameSessionId);
        try {
            return invocations.invoke(
                    runId,
                    ActivityType.MODEL,
                    "interpretAnswerQuestion",
                    estimateTokens(request.toString()),
                    "Player question intent interpreted",
                    () -> deadline.invoke(runId, () -> model.interpretQuestion(request, username)),
                    result -> estimateTokens(result.toString()));
        } finally {
            permit.close();
        }
    }

    private RuleAnswerRateLimiter.Permit acquire(String username, UUID gameSessionId) {
        return rateLimiter.acquireModel(username, gameSessionId, model.providerId(username));
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
