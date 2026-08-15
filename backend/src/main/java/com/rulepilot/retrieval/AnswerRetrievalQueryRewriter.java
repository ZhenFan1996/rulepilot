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
}
