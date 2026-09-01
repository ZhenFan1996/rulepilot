package com.rulepilot.modelconfig;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identifies quota exhaustion through provider and executor exception wrappers. */
public final class ModelAccountQuotaFailures {

    private ModelAccountQuotaFailures() {}

    public static AccountQuotaExceededException find(Throwable failure) {
        if (failure == null) return null;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;
            if (candidate instanceof AccountQuotaExceededException quotaExhausted) return quotaExhausted;
            if (candidate.getCause() != null) pending.addLast(candidate.getCause());
            for (Throwable suppressed : candidate.getSuppressed()) {
                if (suppressed != null) pending.addLast(suppressed);
            }
        }
        return null;
    }

    public static void rethrowIfPresent(Throwable failure) {
        AccountQuotaExceededException quotaExhausted = find(failure);
        if (quotaExhausted != null) throw quotaExhausted;
    }
}
