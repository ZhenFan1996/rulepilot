package com.rulepilot.assistant.application;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ConditionalGeneratedContentCritic implements GeneratedContentCritic {

    private static final int MAX_ISSUES = 12;
    private final ContentCriticModel model;
    private final AuditedAgentInvocations invocations;
    private final boolean evaluationMode;

    public ConditionalGeneratedContentCritic(
            ContentCriticModel model,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.critic.evaluation-mode:false}") boolean evaluationMode) {
        this.model = model;
        this.invocations = invocations;
        this.evaluationMode = evaluationMode;
    }

    @Override
    public Review review(ReviewRequest request, ReviewRisk risk) {
        validateRequest(request);
        if (!evaluationMode && risk != ReviewRisk.LOW_CONFIDENCE && risk != ReviewRisk.HIGH_IMPACT) {
            return new Review(false, List.of());
        }
        var draft = invocations.invoke(
                request.assistantRunId(),
                ActivityType.CRITIC,
                "reviewGeneratedContent",
                estimateTokens(request.toString()),
                "Generated content critique completed",
                () -> model.critique(request),
                result -> estimateTokens(result.toString()));
        if (draft == null || draft.issues().size() > MAX_ISSUES) {
            throw new IllegalArgumentException("critic output is invalid");
        }
        Set<UUID> allowedEvidence = request.evidence().stream()
                .map(GeneratedContentCritic.Evidence::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Integer> claimPositions = request.claims().stream()
                .map(GeneratedContentCritic.Claim::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        draft.issues().forEach(issue -> validateIssue(issue, claimPositions, allowedEvidence));
        return new Review(true, draft.issues());
    }

    private void validateRequest(ReviewRequest request) {
        if (request == null || request.assistantRunId() == null || request.contentType() == null
                || request.taskContext() == null
                || request.taskContext().objective() == null || request.taskContext().objective().isBlank()
                || request.taskContext().requiredCoverage() == null
                || request.taskContext().requiredCoverage().isBlank()
                || request.claims().isEmpty() || request.evidence().isEmpty()
                || request.claims().stream().anyMatch(claim -> claim == null || claim.position() < 1
                        || claim.text() == null || claim.text().isBlank() || claim.citationIds().isEmpty())
                || request.evidence().stream().anyMatch(evidence -> evidence == null || evidence.chunkId() == null
                        || evidence.excerpt() == null || evidence.excerpt().isBlank())) {
            throw new IllegalArgumentException("critic request is invalid");
        }
        Set<Integer> positions = new HashSet<>();
        if (request.claims().stream().anyMatch(claim -> !positions.add(claim.position()))) {
            throw new IllegalArgumentException("critic claim positions must be unique");
        }
    }

    private void validateIssue(Issue issue, Set<Integer> claimPositions, Set<UUID> allowedEvidence) {
        if (issue == null || issue.type() == null || !claimPositions.contains(issue.claimPosition())
                || issue.summary() == null || issue.summary().isBlank() || issue.summary().length() > 240
                || issue.evidenceIds().stream().anyMatch(id -> id == null || !allowedEvidence.contains(id))) {
            throw new IllegalArgumentException("critic issue is invalid");
        }
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
