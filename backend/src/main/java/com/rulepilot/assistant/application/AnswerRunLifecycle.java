package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.UUID;

/** Persists the bounded lifecycle of one answer workflow; answer-state selection remains policy-owned. */
final class AnswerRunLifecycle {

    private final AssistantRuns runs;

    AnswerRunLifecycle(AssistantRuns runs) {
        this.runs = runs;
    }

    RunSnapshot start(AssistantRunMode mode, UUID subjectId, String ownerUsername) {
        return runs.start(mode, subjectId, ownerUsername);
    }

    RunSnapshot finish(RunSnapshot run, StructuredRuleAnswer answer) {
        for (AnswerRunProgressPolicy.ProgressUpdate update : AnswerRunProgressPolicy.updatesFor(answer)) {
            run = runs.advance(run.id(), run.revision(), update.state(), update.summary());
        }
        return run;
    }

    void fail(RunSnapshot run, String errorCode, String summary, RuntimeException workflowFailure) {
        if (run.state().terminal()) {
            return;
        }
        try {
            runs.fail(run.id(), run.revision(), errorCode, summary);
        } catch (RuntimeException trackingFailure) {
            workflowFailure.addSuppressed(trackingFailure);
        }
    }
}
