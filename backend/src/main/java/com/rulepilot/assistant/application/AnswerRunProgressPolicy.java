package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.ArrayList;
import java.util.List;

/** Pure answer-run progress sequencing; persistent transitions remain in the answer workflow. */
final class AnswerRunProgressPolicy {

    private AnswerRunProgressPolicy() {}

    static List<ProgressUpdate> updatesFor(StructuredRuleAnswer answer) {
        List<ProgressUpdate> updates = new ArrayList<>();
        updates.add(new ProgressUpdate(AssistantRunState.QUESTION_UNDERSTANDING, "Question context is normalized"));
        if (answer.status() == AnswerStatus.CLARIFICATION_REQUIRED) {
            updates.add(new ProgressUpdate(AssistantRunState.NEED_CLARIFICATION, "Question requires additional context"));
            return List.copyOf(updates);
        }
        updates.add(new ProgressUpdate(AssistantRunState.RETRIEVAL_PLANNING, "Answer evidence scope is planned"));
        updates.add(new ProgressUpdate(AssistantRunState.RETRIEVING, "Allow-listed answer source lookup completed"));
        updates.add(new ProgressUpdate(AssistantRunState.VERIFYING_EVIDENCE, "Answer source scope is policy checked"));
        if (answer.status() == AnswerStatus.INSUFFICIENT_EVIDENCE || answer.status() == AnswerStatus.VERSION_CONFLICT) {
            updates.add(new ProgressUpdate(AssistantRunState.INSUFFICIENT_EVIDENCE, "Answer evidence is insufficient"));
            return List.copyOf(updates);
        }
        updates.add(new ProgressUpdate(AssistantRunState.ANSWER_COMPOSITION, "Structured cited answer is composed"));
        if (answer.status() != AnswerStatus.ANSWERED) {
            updates.add(new ProgressUpdate(AssistantRunState.DEGRADED, "Answer generation degraded safely"));
            return List.copyOf(updates);
        }
        if (answer.confidence() == AnswerConfidence.LOW) {
            updates.add(new ProgressUpdate(AssistantRunState.CRITIQUING, "Low-confidence answer critique completed"));
        }
        updates.add(new ProgressUpdate(AssistantRunState.COMPLETED, "Question workflow completed"));
        return List.copyOf(updates);
    }

    record ProgressUpdate(AssistantRunState state, String summary) {}
}
