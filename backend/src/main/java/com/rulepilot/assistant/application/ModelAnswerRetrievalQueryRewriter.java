package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.retrieval.AnswerRetrievalQueryRewriter;
import java.util.List;
import java.util.UUID;

/** Supplies optional model rewrites without exposing the answer model contract to retrieval. */
final class ModelAnswerRetrievalQueryRewriter implements AnswerRetrievalQueryRewriter {

    private final AnswerModelGateway modelGateway;

    ModelAnswerRetrievalQueryRewriter(AnswerModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    @Override
    public List<String> rewrite(UUID runId, String username, String question, String previousQuestion) {
        return modelGateway.rewriteRetrievalQueries(
                runId, username, new RetrievalQueryRequest(question, previousQuestion));
    }

    @Override
    public boolean timedOut(RuntimeException failure) {
        return failure instanceof RuleAnswerModelTimeoutException;
    }
}
