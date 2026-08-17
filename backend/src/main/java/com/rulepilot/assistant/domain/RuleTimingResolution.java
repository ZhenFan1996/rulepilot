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
        required(timingContext, "timing context");
        required(resolutionOrder, "resolution order");
        required(orderSource, "order source");
        if (basis == null || citationIds == null || citationIds.isEmpty()
                || citationIds.stream().distinct().count() != citationIds.size()) {
            throw new IllegalArgumentException("rule timing resolution is invalid");
        }
        citationIds = List.copyOf(citationIds);
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rule timing " + field + " is invalid");
        }
    }
}
