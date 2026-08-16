package com.rulepilot.retrieval;

import java.util.List;
import java.util.UUID;

/** Optional bounded cross-language rewrite supplied by the calling answer workflow. */
@FunctionalInterface
public interface AnswerRetrievalQueryRewriter {

    List<String> rewrite(UUID runId, String username, String question, String previousQuestion);

    default boolean timedOut(RuntimeException failure) {
        return false;
    }

    /**
     * Multilingual vector search handles the normal first pass. A native evidence Agent may reformulate only after
     * observing a real evidence gap, so the deterministic retrieval path must not pay for an unconditional rewrite.
     */
    static AnswerRetrievalQueryRewriter none() {
        return (runId, username, question, previousQuestion) -> List.of();
    }
}
