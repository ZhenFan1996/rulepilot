package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

/** A cited, player-visible resolution of a simultaneous-effect ordering question. */
public record RuleTimingResolution(
        String timingContext,
        String resolutionOrder,
        String orderSource,
        TimingOrderBasis basis,
        List<UUID> citationIds) {

    public RuleTimingResolution {
        bounded(timingContext, 500, "timing context");
        bounded(resolutionOrder, 700, "resolution order");
        bounded(orderSource, 400, "order source");
        if (basis == null || citationIds == null || citationIds.isEmpty() || citationIds.size() > 3
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("rule timing resolution is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }

    private static void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("rule timing " + field + " is invalid");
        }
    }
}
