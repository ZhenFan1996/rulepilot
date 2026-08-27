package com.rulepilot.assistant.application;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AgentInvocationDeadline deadline;

    @Autowired
    public ConditionalGeneratedContentCritic(
            ContentCriticModel model,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.critic.evaluation-mode:false}") boolean evaluationMode,
            AgentInvocationDeadline deadline) {
        this.model = model;
        this.invocations = invocations;
        this.evaluationMode = evaluationMode;
        this.deadline = deadline;
    }

    public ConditionalGeneratedContentCritic(
            ContentCriticModel model,
            AuditedAgentInvocations invocations,
            boolean evaluationMode) {
        this(model, invocations, evaluationMode, AgentInvocationDeadline.unbounded());
    }

    @Override
    public Review review(ReviewRequest request, ReviewRisk risk) {
        return review(request, risk, null);
    }

    @Override
    public Review review(ReviewRequest request, ReviewRisk risk, String ownerUsername) {
        // Answers and ordinary evaluation probes keep the zero-latency runtime path. Teaching calls this mode only
        // after a chapter with a quantitative or legality-changing relationship has remained CITED_DRAFT; measured
        // rulebook canaries have shown that identity-valid citations alone do not catch swapped counts or a dropped
        // multiplier, so that one bounded whole-lesson review is a publication boundary rather than optional polish.
        boolean requiredTeachingPublicationReview = request != null
                && request.contentType() == ContentType.LESSON
                && request.reviewMode() == ReviewMode.POST_PUBLICATION;
        if (!evaluationMode && !requiredTeachingPublicationReview) {
            return new Review(false, List.of());
        }
        validateRequest(request);
        String operation = switch (request.reviewMode()) {
            case OBJECTIVE_COVERAGE -> "reviewObjectiveCoverage";
            case POST_PUBLICATION -> "reviewPublishedTeachingLesson";
            case POST_PUBLICATION_STRUCTURE -> "reviewPublishedTeachingStructure";
            default -> "reviewGeneratedContent";
        };
        String successSummary = switch (request.reviewMode()) {
            case OBJECTIVE_COVERAGE -> "Objective coverage critique completed";
            case POST_PUBLICATION -> "Published teaching lesson review completed";
            case POST_PUBLICATION_STRUCTURE -> "Published teaching lesson structure review completed";
            default -> "Generated content critique completed";
        };
        List<Issue> candidates = critique(request, operation, successSummary, ownerUsername);
        if (candidates.isEmpty() || !requiresAtomicConfirmation(request.reviewMode())) {
            return new Review(true, candidates);
        }
        return new Review(true, confirmCandidateIssues(request, candidates, ownerUsername));
    }

    private boolean requiresAtomicConfirmation(ReviewMode mode) {
        return mode == ReviewMode.DISCOVERY || mode == ReviewMode.POST_PUBLICATION;
    }

    private List<Issue> critique(
            ReviewRequest request,
            String operation,
            String successSummary,
            String ownerUsername) {
        var draft = invocations.invoke(
                request.assistantRunId(),
                ActivityType.CRITIC,
                operation,
                estimateTokens(request.toString()),
                successSummary,
                () -> deadline.invoke(
                        request.assistantRunId(), () -> model.critique(request, ownerUsername)),
                result -> estimateTokens(result.toString()));
        if (draft == null) {
            throw new IllegalArgumentException("critic output is invalid");
        }
        Set<UUID> allowedEvidence = request.evidence().stream()
                .map(GeneratedContentCritic.Evidence::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Integer> claimPositions = request.claims().stream()
                .map(GeneratedContentCritic.Claim::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return draft.issues().stream()
                .map(issue -> normalizeIssue(issue, claimPositions, allowedEvidence))
                .limit(MAX_ISSUES)
                .toList();
    }

    private List<Issue> confirmCandidateIssues(
            ReviewRequest request, List<Issue> candidates, String ownerUsername) {
        Map<Integer, Set<CandidateDefect>> candidateDefectsByPosition = candidates.stream()
                .collect(Collectors.groupingBy(
                        Issue::claimPosition,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                issue -> new CandidateDefect(issue.type(), issue.claimAspect()),
                                Collectors.toUnmodifiableSet())));
        Set<Integer> candidatePositions = Set.copyOf(candidateDefectsByPosition.keySet());
        List<GeneratedContentCritic.Claim> contextualClaims = request.claims().stream()
                .filter(claim -> candidatePositions.contains(claim.position()))
                .sorted(Comparator.comparingInt(GeneratedContentCritic.Claim::position))
                .toList();
        Set<UUID> contextualCitationIds = contextualClaims.stream()
                .flatMap(claim -> claim.citationIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        List<GeneratedContentCritic.Evidence> contextualEvidence = request.evidence().stream()
                .filter(evidence -> contextualCitationIds.contains(evidence.chunkId()))
                .toList();
        if (contextualClaims.isEmpty() || contextualEvidence.isEmpty()) {
            throw new IllegalArgumentException("critic candidates cannot be confirmed without cited evidence");
        }

        ReviewRequest confirmationRequest = new ReviewRequest(
                request.assistantRunId(),
                request.contentType(),
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext(
                        "Independently confirm candidate factual defects without relying on the discovery verdict.",
                        atomicCoverage(candidateDefectsByPosition)),
                contextualClaims,
                contextualEvidence);
        Map<Integer, Set<UUID>> claimEvidenceByPosition = contextualClaims.stream()
                .collect(Collectors.toUnmodifiableMap(
                        GeneratedContentCritic.Claim::position,
                        claim -> Set.copyOf(claim.citationIds())));
        return critique(
                        confirmationRequest,
                        "confirmGeneratedClaims",
                        "Candidate claim defects independently confirmed",
                        ownerUsername)
                .stream()
                .filter(issue -> candidateDefectsByPosition
                        .getOrDefault(issue.claimPosition(), Set.of())
                        .contains(new CandidateDefect(issue.type(), issue.claimAspect())))
                .map(issue -> scopeEvidenceToClaim(issue, claimEvidenceByPosition))
                .distinct()
                .sorted(Comparator.comparingInt(Issue::claimPosition)
                        .thenComparing(Issue::type)
                        .thenComparing(Issue::summary))
                .limit(MAX_ISSUES)
                .toList();
    }

    private String atomicCoverage(Map<Integer, Set<CandidateDefect>> candidateDefectsByPosition) {
        String candidates = candidateDefectsByPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=[" + entry.getValue().stream()
                        .sorted(Comparator.comparing(CandidateDefect::type)
                                .thenComparing(CandidateDefect::claimAspect))
                        .map(defect -> defect.type().name() + "/" + defect.claimAspect().name())
                        .collect(Collectors.joining(", ")) + "]")
                .collect(Collectors.joining("; "));
        return "Confirm only these candidate issue type/aspect pairs by claim position: " + candidates
                + ". The confirmation request contains only candidate claims and their own cited evidence. Judge "
                + "each complete claim relation only against its own cited evidence IDs; return no issue for a "
                + "supported claim.";
    }

    private Issue scopeEvidenceToClaim(Issue issue, Map<Integer, Set<UUID>> claimEvidenceByPosition) {
        Set<UUID> claimEvidence = claimEvidenceByPosition.getOrDefault(issue.claimPosition(), Set.of());
        List<UUID> scopedEvidence = issue.evidenceIds().stream()
                .filter(claimEvidence::contains)
                .toList();
        if (scopedEvidence.isEmpty()) {
            throw new IllegalArgumentException("confirmed critic issue must cite its own cited evidence");
        }
        return new Issue(
                issue.type(), issue.claimAspect(), issue.claimPosition(), scopedEvidence, issue.summary());
    }

    private void validateRequest(ReviewRequest request) {
        if (request == null || request.assistantRunId() == null || request.contentType() == null
                || request.reviewMode() == null
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
        Set<UUID> evidenceIds = request.evidence().stream()
                .map(GeneratedContentCritic.Evidence::chunkId)
                .collect(Collectors.toUnmodifiableSet());
        if (request.claims().stream().flatMap(claim -> claim.citationIds().stream())
                .anyMatch(citationId -> citationId == null || !evidenceIds.contains(citationId))) {
            throw new IllegalArgumentException("critic claim citations must reference supplied evidence");
        }
    }

    private Issue normalizeIssue(Issue issue, Set<Integer> claimPositions, Set<UUID> allowedEvidence) {
        if (issue == null || issue.type() == null || issue.claimAspect() == null
                || issue.summary() == null || issue.summary().isBlank()
                || issue.summary().length() > 240
                || !claimPositions.contains(issue.claimPosition())) {
            throw new IllegalArgumentException("critic issue is invalid");
        }
        if (issue.evidenceIds().isEmpty()
                || issue.evidenceIds().stream().anyMatch(id -> id == null || !allowedEvidence.contains(id))) {
            throw new IllegalArgumentException("critic issue must cite its own cited evidence");
        }
        return new Issue(
                issue.type(),
                issue.claimAspect(),
                issue.claimPosition(),
                issue.evidenceIds().stream().distinct().toList(),
                issue.summary().strip());
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }

    private record CandidateDefect(IssueType type, ClaimAspect claimAspect) {}
}
