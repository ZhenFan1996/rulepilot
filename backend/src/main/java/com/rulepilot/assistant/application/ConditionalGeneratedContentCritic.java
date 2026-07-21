package com.rulepilot.assistant.application;

import com.rulepilot.assistant.ContentCriticModel;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewMode;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ConditionalGeneratedContentCritic implements GeneratedContentCritic {

    private static final int MAX_ISSUES = 12;
    private static final Pattern TERMINAL_NO_DEFECT = Pattern.compile(
            "(?is).*(无问题|没有问题|无缺陷|无新增缺陷|无异议|无矛盾|非规则错误|支持该部分|证据支持|no (?:concrete )?(?:issue|defect)|no contradiction|fully supported|is supported|is consistent)[。.!！）)\\s]*$");
    private final ContentCriticModel model;
    private final AuditedAgentInvocations invocations;
    private final boolean evaluationMode;
    private final int atomicConfirmationConcurrency;

    @Autowired
    public ConditionalGeneratedContentCritic(
            ContentCriticModel model,
            AuditedAgentInvocations invocations,
            @Value("${rulepilot.critic.evaluation-mode:false}") boolean evaluationMode,
            @Value("${rulepilot.critic.atomic-confirmation-concurrency:4}") int atomicConfirmationConcurrency) {
        this.model = model;
        this.invocations = invocations;
        this.evaluationMode = evaluationMode;
        this.atomicConfirmationConcurrency = Math.max(1, atomicConfirmationConcurrency);
    }

    ConditionalGeneratedContentCritic(
            ContentCriticModel model,
            AuditedAgentInvocations invocations,
            boolean evaluationMode) {
        this(model, invocations, evaluationMode, 4);
    }

    @Override
    public Review review(ReviewRequest request, ReviewRisk risk) {
        validateRequest(request);
        if (!evaluationMode && risk != ReviewRisk.LOW_CONFIDENCE && risk != ReviewRisk.HIGH_IMPACT) {
            return new Review(false, List.of());
        }
        String operation = switch (request.reviewMode()) {
            case OBJECTIVE_COVERAGE -> "reviewObjectiveCoverage";
            case POST_PUBLICATION -> "reviewPublishedTeachingLesson";
            default -> "reviewGeneratedContent";
        };
        String successSummary = switch (request.reviewMode()) {
            case OBJECTIVE_COVERAGE -> "Objective coverage critique completed";
            case POST_PUBLICATION -> "Published teaching lesson review completed";
            default -> "Generated content critique completed";
        };
        List<Issue> candidateIssues = critique(request, operation, successSummary);
        if (candidateIssues.isEmpty() || request.reviewMode() != ReviewMode.DISCOVERY) {
            return new Review(true, candidateIssues);
        }
        return new Review(true, confirmAtomicIssues(request, candidateIssues));
    }

    private List<Issue> critique(ReviewRequest request, String operation, String successSummary) {
        var draft = invocations.invoke(
                request.assistantRunId(),
                ActivityType.CRITIC,
                operation,
                estimateTokens(request.toString()),
                successSummary,
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
        return draft.issues().stream()
                .filter(issue -> issue == null
                        || issue.summary() == null
                        || !explicitlyDescribesNoDefect(issue.summary()))
                .map(issue -> normalizeIssue(issue, claimPositions, allowedEvidence))
                .toList();
    }

    private List<Issue> confirmAtomicIssues(ReviewRequest request, List<Issue> candidates) {
        Map<Integer, List<Issue>> byClaimPosition = candidates.stream()
                .collect(Collectors.groupingBy(
                        Issue::claimPosition,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<Map.Entry<Integer, List<Issue>>> groups = byClaimPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        try (var executor = Executors.newFixedThreadPool(Math.min(atomicConfirmationConcurrency, groups.size()))) {
            var confirmations = groups.stream()
                    .map(group -> executor.submit(() -> confirmClaimIssues(request, group.getKey(), group.getValue())))
                    .toList();
            List<Issue> confirmed = new ArrayList<>();
            for (var confirmation : confirmations) {
                confirmed.addAll(confirmation.get());
            }
            return confirmed.stream()
                    .sorted(Comparator.comparingInt(Issue::claimPosition).thenComparing(Issue::type))
                    .toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("atomic critic confirmation was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("atomic critic confirmation failed", exception.getCause());
        }
    }

    private List<Issue> confirmClaimIssues(ReviewRequest request, int claimPosition, List<Issue> candidates) {
        var claim = request.claims().stream()
                .filter(candidate -> candidate.position() == claimPosition)
                .findFirst()
                .orElseThrow();
        Set<UUID> citationIds = Set.copyOf(claim.citationIds());
        List<GeneratedContentCritic.Evidence> citedEvidence = request.evidence().stream()
                .filter(evidence -> citationIds.contains(evidence.chunkId()))
                .toList();
        if (citedEvidence.isEmpty()) {
            return List.of();
        }
        Set<IssueType> candidateTypes = candidates.stream()
                .map(Issue::type)
                .collect(Collectors.toUnmodifiableSet());
        String types = candidateTypes.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        ReviewRequest confirmationRequest = new ReviewRequest(
                request.assistantRunId(),
                request.contentType(),
                ReviewMode.ATOMIC_CONFIRMATION,
                new TaskContext(
                        "Independently confirm candidate factual defects for claim position " + claimPosition + ".",
                        "Confirm only these candidate issue types: " + types
                                + ". Use the claim's cited evidence as one combined evidence set."),
                List.of(claim),
                citedEvidence);
        return critique(
                        confirmationRequest,
                        "confirmGeneratedClaim" + claimPosition,
                        "Atomic claim critique completed")
                .stream()
                .filter(issue -> candidateTypes.contains(issue.type()))
                .toList();
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
        if (issue == null || issue.type() == null || issue.summary() == null || issue.summary().isBlank()) {
            throw new IllegalArgumentException("critic issue is invalid");
        }
        int claimPosition = claimPositions.contains(issue.claimPosition())
                ? issue.claimPosition()
                : claimPositions.stream().min(Integer::compareTo).orElseThrow();
        List<UUID> evidenceIds = issue.evidenceIds().stream()
                .filter(id -> id != null && allowedEvidence.contains(id))
                .distinct()
                .toList();
        String summary = issue.summary().strip();
        if (summary.length() > 240) {
            summary = summary.substring(0, 240).stripTrailing();
        }
        return new Issue(issue.type(), claimPosition, evidenceIds, summary);
    }

    private boolean explicitlyDescribesNoDefect(String summary) {
        String normalized = summary.toLowerCase(java.util.Locale.ROOT);
        if (TERMINAL_NO_DEFECT.matcher(normalized).matches()) {
            return true;
        }
        boolean contrast = normalized.contains(" but ")
                || normalized.contains("however")
                || normalized.contains("although")
                || normalized.contains("except")
                || normalized.contains(" yet ")
                || normalized.contains("；但")
                || normalized.contains("，但")
                || normalized.contains("但是")
                || normalized.contains("却");
        boolean noDefectConclusion = normalized.contains("no issue")
                || normalized.contains("no defect")
                || normalized.contains("no concrete issue")
                || normalized.contains("no contradiction")
                || normalized.contains("no actual error")
                || normalized.contains("无问题")
                || normalized.contains("没有问题")
                || normalized.contains("无异议")
                || normalized.contains("无矛盾")
                || normalized.contains("非规则错误")
                || normalized.contains("supported")
                || normalized.contains("correct")
                || normalized.contains("accurate")
                || normalized.contains("acceptable")
                || normalized.contains("matches the evidence")
                || normalized.contains("matches the claim")
                || normalized.contains("is consistent")
                || normalized.contains("both correct")
                || normalized.contains("有证据支持")
                || normalized.contains("得到证据支持")
                || normalized.contains("证据支持")
                || normalized.contains("表述正确")
                || normalized.contains("准确无误");
        boolean describesDefect = normalized.contains("unsupported")
                || normalized.contains("not supported")
                || normalized.contains("does not")
                || normalized.contains("doesn't")
                || normalized.contains("not state")
                || normalized.contains("not correct")
                || normalized.contains("not accurate")
                || normalized.contains("not acceptable")
                || normalized.contains("incorrect")
                || normalized.contains("contradict")
                || normalized.contains("invent")
                || normalized.contains("missing")
                || normalized.contains("omit")
                || normalized.contains("however")
                || normalized.contains(" but ")
                || normalized.contains("不支持")
                || normalized.contains("不正确")
                || normalized.contains("未说明")
                || normalized.contains("未提及")
                || normalized.contains("错误")
                || normalized.contains("矛盾")
                || normalized.contains("虚构")
                || normalized.contains("遗漏")
                || normalized.contains("但是");
        if (describesDefect) {
            return false;
        }
        return noDefectConclusion && !contrast;
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
