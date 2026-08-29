package com.rulepilot.teaching.application;

/** Outcome of one idempotent attempt to settle a Teaching queue terminal boundary. */
enum TeachingTerminalRecordResult {
    /** The requested boundary is persisted or another durable winner makes this intent obsolete. */
    SETTLED,

    /** No durable winner is known and a transient infrastructure failure makes a later attempt useful. */
    RETRYABLE
}
