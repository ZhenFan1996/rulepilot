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
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ConditionalGeneratedContentCritic implements GeneratedContentCritic {

    private static final int MAX_ISSUES = 12;
    private static final Pattern TERMINAL_NO_DEFECT = Pattern.compile(
            "(?is).*(无缺陷|无新增缺陷|无异议|无矛盾|非规则错误|支持该部分|证据支持|no (?:concrete )?(?:issue|defect)|no contradiction|fully supported|is supported|is consistent)[。.!\\s]*$");
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
        List<Issue> normalizedIssues = draft.issues().stream()
                .filter(issue -> issue == null
                        || issue.summary() == null
                        || (!explicitlyDescribesNoDefect(issue.summary())
                                && !rejectsRequiredChineseTranslation(issue.summary())))
                .map(issue -> normalizeIssue(issue, claimPositions, allowedEvidence))
                .toList();
        return new Review(true, normalizedIssues);
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

    private boolean rejectsRequiredChineseTranslation(String summary) {
        String normalized = summary.toLowerCase(java.util.Locale.ROOT);
        boolean translationComplaint = normalized.contains("translation")
                || normalized.contains("correct is")
                || normalized.contains("should be")
                || normalized.contains("应译")
                || normalized.contains("应为英文");
        if (!translationComplaint) {
            return false;
        }
        return containsPair(normalized, "信用点", "credit")
                || containsPair(normalized, "能量", "energy")
                || containsPair(normalized, "宣传度", "publicity")
                || containsPair(normalized, "探测器", "probe")
                || containsPair(normalized, "数据", "data")
                || containsPair(normalized, "科技", "tech");
    }

    private boolean containsPair(String value, String chinese, String sourceTerm) {
        return value.contains(chinese) && value.contains(sourceTerm);
    }

    private int estimateTokens(String value) {
        return value == null ? 0 : Math.max(1, (value.length() + 3) / 4);
    }
}
