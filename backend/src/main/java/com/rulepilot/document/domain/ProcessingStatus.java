package com.rulepilot.document.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ProcessingStatus {
    UPLOADED,
    VALIDATING,
    EXTRACTING,
    STRUCTURING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    READY,
    FAILED;

    public boolean canTransitionTo(ProcessingStatus next) {
        if (next == FAILED) {
            return this != READY && this != FAILED;
        }
        return switch (this) {
            case UPLOADED -> next == VALIDATING;
            case VALIDATING -> next == EXTRACTING;
            case EXTRACTING -> next == STRUCTURING;
            case STRUCTURING -> next == CHUNKING;
            case CHUNKING -> next == EMBEDDING;
            case EMBEDDING -> next == INDEXING;
            case INDEXING -> next == READY;
            case READY, FAILED -> false;
        };
    }

    public Set<ProcessingStatus> allowedNextStatuses() {
        return EnumSet.allOf(ProcessingStatus.class).stream()
                .filter(this::canTransitionTo)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
