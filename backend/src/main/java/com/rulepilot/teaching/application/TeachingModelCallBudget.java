package com.rulepilot.teaching.application;

/** Shares one bounded provider-call allowance across every recovery owner for a teaching section. */
final class TeachingModelCallBudget {

    private static final int MAX_SECTION_CALLS = 3;
    private static final int MAX_STRUCTURED_OPERATION_CALLS = 2;

    private final int maximum;
    private int used;

    private TeachingModelCallBudget(int maximum) {
        this.maximum = maximum;
    }

    static TeachingModelCallBudget section() {
        return new TeachingModelCallBudget(MAX_SECTION_CALLS);
    }

    static TeachingModelCallBudget structuredOperation() {
        return new TeachingModelCallBudget(MAX_STRUCTURED_OPERATION_CALLS);
    }

    static int maximumSectionCalls() {
        return MAX_SECTION_CALLS;
    }

    synchronized void acquire() {
        if (used >= maximum) {
            throw new IllegalStateException("teaching model recovery budget is exhausted");
        }
        used++;
    }

    synchronized boolean hasRemaining() {
        return used < maximum;
    }
}
