package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException.RejectedOutput;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Answer-model calls with one permit lifecycle, durable execution controls, and player-visible audit activity. */
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

    Composition compose(UUID runId, String username, UUID gameSessionId, ModelRequest request) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, gameSessionId);
        try {
            Set<RejectedOutput> rejectedOutputs = new HashSet<>();
            RejectedOutput rejectedOutput = null;
            int replacements = 0;
            while (true) {
                try {
                    ModelDraft draft = rejectedOutput == null
                            ? invokeComposition(runId, username, request)
                            : invokeReplacement(runId, username, request, rejectedOutput);
                    if (draft == null) {
                        throw new RuleAnswerModelInvalidOutputException(
                                "answer model returned no structured output");
                    }
                    return new Composition(draft, replacements);
                } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
                    RejectedOutput next = invalidOutput.rejectedOutput().orElseThrow(() -> invalidOutput);
                    if (!rejectedOutputs.add(next)) throw invalidOutput;
                    rejectedOutput = next;
                    replacements++;
                }
            }
        } finally {
            permit.close();
        }
    }

    private ModelDraft invokeComposition(
            UUID runId, String username, ModelRequest request) {
        return invocations.invoke(
                runId,
                ActivityType.MODEL,
                "composeRuleAnswer",
                estimateTokens(request.toString()),
                "Rule answer model output received",
                () -> deadline.invoke(runId, () -> model.compose(request, username)),
                result -> estimateTokens(result.toString()));
    }

    private ModelDraft invokeReplacement(
            UUID runId,
            String username,
            ModelRequest request,
            RejectedOutput rejectedOutput) {
        return invocations.invoke(
                runId,
                ActivityType.MODEL,
                "replaceInvalidRuleAnswerOutput",
                estimateTokens(request.toString()) + estimateTokens(rejectedOutput.toString()),
                "Invalid answer envelope replaced as one complete response",
                () -> deadline.invoke(
                        runId,
                        () -> model.replaceInvalidOutput(request, rejectedOutput, username)),
                result -> estimateTokens(result.toString()));
    }

    Composition continueAfterValidationRejection(
            UUID runId,
            String username,
            UUID gameSessionId,
            ModelRequest request,
            ModelDraft rejectedDraft,
            String validationError,
            String operation,
            String successSummary) {
        RuleAnswerRateLimiter.Permit permit = acquire(username, gameSessionId);
        try {
            Set<RejectedOutput> rejectedOutputs = new HashSet<>();
            RejectedOutput rejectedOutput = null;
            int replacements = 1;
            while (true) {
                try {
                    ModelDraft draft = rejectedOutput == null
                            ? invokeValidationReplacement(
                                    runId,
                                    username,
                                    request,
                                    rejectedDraft,
                                    validationError,
                                    operation,
                                    successSummary)
                            : invokeReplacement(runId, username, request, rejectedOutput);
                    if (draft == null) {
                        throw new RuleAnswerModelInvalidOutputException(
                                "answer model returned no structured replacement output");
                    }
                    return new Composition(draft, replacements);
                } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
                    RejectedOutput next = invalidOutput.rejectedOutput().orElseThrow(() -> invalidOutput);
                    if (!rejectedOutputs.add(next)) throw invalidOutput;
                    rejectedOutput = next;
                    replacements++;
                }
            }
        } finally {
            permit.close();
        }
    }

    private ModelDraft invokeValidationReplacement(
            UUID runId,
            String username,
            ModelRequest request,
            ModelDraft rejectedDraft,
            String validationError,
            String operation,
            String successSummary) {
        return invocations.invoke(
                runId,
                ActivityType.MODEL,
                operation,
                estimateTokens(request.toString())
                        + estimateTokens(rejectedDraft.toString())
                        + estimateTokens(validationError),
                successSummary,
                () -> deadline.invoke(
                        runId,
                        () -> model.replaceValidationRejectedOutput(
                                request, rejectedDraft, validationError, username)),
                result -> estimateTokens(result.toString()));
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
            Set<RejectedOutput> rejectedOutputs = new HashSet<>();
            RejectedOutput rejectedOutput = null;
            while (true) {
                try {
                    Optional<QuestionInterpretationDraft> interpretation = rejectedOutput == null
                            ? invokeQuestionInterpretation(runId, username, request)
                            : invokeQuestionInterpretationReplacement(
                                    runId, username, request, rejectedOutput);
                    if (interpretation == null) {
                        throw new RuleAnswerModelInvalidOutputException(
                                "answer question interpretation returned no structured output");
                    }
                    return interpretation;
                } catch (RuleAnswerModelInvalidOutputException invalidOutput) {
                    RejectedOutput next = invalidOutput.rejectedOutput().orElseThrow(() -> invalidOutput);
                    if (!rejectedOutputs.add(next)) throw invalidOutput;
                    rejectedOutput = next;
                }
            }
        } finally {
            permit.close();
        }
    }

    private Optional<QuestionInterpretationDraft> invokeQuestionInterpretation(
            UUID runId, String username, QuestionInterpretationRequest request) {
        return invocations.invoke(
                runId,
                ActivityType.MODEL,
                "interpretAnswerQuestion",
                estimateTokens(request.toString()),
                "Player question intent interpreted",
                () -> deadline.invoke(runId, () -> model.interpretQuestion(request, username)),
                result -> estimateTokens(result.toString()));
    }

    private Optional<QuestionInterpretationDraft> invokeQuestionInterpretationReplacement(
            UUID runId,
            String username,
            QuestionInterpretationRequest request,
            RejectedOutput rejectedOutput) {
        return invocations.invoke(
                runId,
                ActivityType.MODEL,
                "replaceInvalidQuestionInterpretation",
                estimateTokens(request.toString()) + estimateTokens(rejectedOutput.toString()),
                "Invalid question interpretation replaced as one complete response",
                () -> deadline.invoke(
                        runId,
                        () -> model.replaceInvalidQuestionInterpretation(
                                request, rejectedOutput, username)),
                result -> estimateTokens(result.toString()));
    }

    private RuleAnswerRateLimiter.Permit acquire(String username, UUID gameSessionId) {
        return rateLimiter.acquireModel(username, gameSessionId, model.providerId(username));
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    record Composition(ModelDraft draft, int replacements) {}
}
