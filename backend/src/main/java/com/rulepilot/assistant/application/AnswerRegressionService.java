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
        List<CaseResult> results = new ArrayList<>();
        long totalLatency = 0;
        for (AnswerRegressionCase testCase : regressionSet.cases()) {
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
            results.add(check(testCase, creation, latency));
        }
        int passed = (int) results.stream().filter(CaseResult::passed).count();
        return new AnswerRegressionReport(
                regressionSet.name(), documentVersionId, results.size(), passed, totalLatency, results);
    }

    private CaseResult check(AnswerRegressionCase testCase, AnswerCreation creation, long latency) {
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
                testCase.id(), failures.isEmpty(), answer.status(), List.copyOf(pages), failures,
                latency, creation.assistantRunId());
    }
}
