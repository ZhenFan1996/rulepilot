package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AnswerRegressionSet;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.domain.AnswerRegressionCase;
import com.rulepilot.assistant.domain.AnswerRegressionReport;
import com.rulepilot.assistant.domain.AnswerRegressionReport.CaseResult;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class AnswerRegressionService {

    private final StructuredRuleAnswerService answers;
    private final AnswerRegressionSet regressionSet;

    public AnswerRegressionService(StructuredRuleAnswerService answers, AnswerRegressionSet regressionSet) {
        this.answers = answers;
        this.regressionSet = regressionSet;
    }

    public AnswerRegressionReport evaluate(UUID documentVersionId, String username) {
        return evaluate(documentVersionId, username, 1, null);
    }

    public AnswerRegressionReport evaluate(UUID documentVersionId, String username, int attempts) {
        return evaluate(documentVersionId, username, attempts, null);
    }

    public AnswerRegressionReport evaluate(
            UUID documentVersionId, String username, int attempts, String caseId) {
        if (attempts < 1 || attempts > 3) {
            throw new IllegalArgumentException("answer regression attempts must be between 1 and 3");
        }
        List<AnswerRegressionCase> selectedCases = caseId == null || caseId.isBlank()
                ? regressionSet.cases()
                : regressionSet.cases().stream().filter(testCase -> testCase.id().equals(caseId)).toList();
        if (selectedCases.isEmpty()) {
            throw new IllegalArgumentException(caseId == null || caseId.isBlank()
                    ? "answer regression dataset is not configured"
                    : "answer regression case does not exist");
        }
        List<CaseResult> results = new ArrayList<>();
        long totalLatency = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            for (AnswerRegressionCase testCase : selectedCases) {
                Instant started = Instant.now();
                AnswerCreation creation = answers.evaluateWithRun(
                        testCase.question(),
                        new QuestionContext(
                                documentVersionId,
                                null,
                                testCase.gamePhase(),
                                testCase.playerCount(),
                                Set.of(),
                                testCase.previousQuestion(),
                                null),
                        username,
                        documentVersionId);
                long latency = Duration.between(started, Instant.now()).toMillis();
                totalLatency += latency;
                results.add(check(testCase, attempt, creation, latency));
            }
        }
        int passedExecutions = (int) results.stream().filter(CaseResult::passed).count();
        int stableCases = (int) selectedCases.stream()
                .filter(testCase -> results.stream()
                        .filter(result -> result.caseId().equals(testCase.id()))
                        .allMatch(CaseResult::passed))
                .count();
        return new AnswerRegressionReport(
                regressionSet.name(), documentVersionId, selectedCases.size(), attempts,
                results.size(), passedExecutions, stableCases, totalLatency, results);
    }

    private CaseResult check(
            AnswerRegressionCase testCase, int attempt, AnswerCreation creation, long latency) {
        StructuredRuleAnswer answer = creation.answer();
        List<String> failures = new ArrayList<>();
        if (answer.status() != testCase.expectedStatus()) {
            failures.add("STATUS");
        }
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        for (RuleCitation citation : answer.citations()) {
            for (int page = citation.pageFrom(); page <= citation.pageTo(); page++) {
                pages.add(page);
            }
        }
        for (Integer requiredPage : testCase.requiredPages()) {
            if (!pages.contains(requiredPage)) failures.add("MISSING_PAGE_" + requiredPage);
        }
        String content = (answer.shortVerdict() + " " + answer.explanation()).toLowerCase(Locale.ROOT);
        for (int index = 0; index < testCase.requiredTermGroups().size(); index++) {
            boolean present = testCase.requiredTermGroups().get(index).stream()
                    .map(term -> term.toLowerCase(Locale.ROOT))
                    .anyMatch(content::contains);
            if (!present) failures.add("MISSING_TERM_GROUP_" + (index + 1));
        }
        for (String forbidden : testCase.forbiddenTerms()) {
            if (content.contains(forbidden.toLowerCase(Locale.ROOT))) failures.add("FORBIDDEN_TERM");
        }
        if (latency > testCase.maxLatencyMillis()) failures.add("LATENCY_BUDGET");
        return new CaseResult(
                testCase.id(), attempt, failures.isEmpty(), answer.status(), List.copyOf(pages), failures,
                latency, creation.assistantRunId());
    }
}
