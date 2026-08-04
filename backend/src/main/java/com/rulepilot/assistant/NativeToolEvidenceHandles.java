package com.rulepilot.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Extracts bounded untrusted evidence handles with exact-page observations ahead of broad searches. */
public final class NativeToolEvidenceHandles {

    private NativeToolEvidenceHandles() {}

    public static Set<UUID> prioritized(NativeToolAgent.RunResult result, int maximum) {
        if (result == null || maximum < 1 || maximum > 100) {
            throw new IllegalArgumentException("native evidence handle request is invalid");
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        List<NativeToolAgent.ObservationRecord> observations = result.observations();
        for (int index = observations.size() - 1; index >= 0; index--) {
            var record = observations.get(index);
            if ("read_rule_pages".equals(record.toolName())) add(record, ids, maximum);
            if (ids.size() >= maximum) return ordered(ids);
        }
        for (int index = observations.size() - 1; index >= 0; index--) {
            var record = observations.get(index);
            if (!"read_rule_pages".equals(record.toolName())) add(record, ids, maximum);
            if (ids.size() >= maximum) return ordered(ids);
        }
        return ordered(ids);
    }

    /** Returns bounded exact-page observation batches in newest-first order without trusting their page metadata. */
    public static List<Set<UUID>> exactPageObservationGroups(
            NativeToolAgent.RunResult result, int maximumGroups, int maximumPerGroup) {
        if (result == null || maximumGroups < 1 || maximumGroups > 20
                || maximumPerGroup < 1 || maximumPerGroup > 100) {
            throw new IllegalArgumentException("native page observation group request is invalid");
        }
        java.util.ArrayList<Set<UUID>> groups = new java.util.ArrayList<>();
        List<NativeToolAgent.ObservationRecord> observations = result.observations();
        for (int index = observations.size() - 1; index >= 0 && groups.size() < maximumGroups; index--) {
            var record = observations.get(index);
            if (!"read_rule_pages".equals(record.toolName())) continue;
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            add(record, ids, maximumPerGroup);
            if (!ids.isEmpty()) groups.add(ordered(ids));
        }
        return List.copyOf(groups);
    }

    private static void add(
            NativeToolAgent.ObservationRecord record, LinkedHashSet<UUID> ids, int maximum) {
        addValues(record.observation().data().get("evidence"), ids, maximum);
        if (ids.size() >= maximum) return;
        addValues(record.observation().data().get("anchors"), ids, maximum);
        if (ids.size() >= maximum) return;
        addValues(record.observation().data().get("surroundingEvidence"), ids, maximum);
    }

    private static void addValues(Object evidence, LinkedHashSet<UUID> ids, int maximum) {
        if (!(evidence instanceof List<?> values)) return;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Object evidenceId = map.get("evidenceId");
            if (!(evidenceId instanceof String candidate)) continue;
            try {
                ids.add(UUID.fromString(candidate));
            } catch (IllegalArgumentException ignored) {
                // Observations are untrusted; malformed handles never reach an evidence adapter.
            }
            if (ids.size() >= maximum) return;
        }
    }

    private static Set<UUID> ordered(LinkedHashSet<UUID> ids) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }
}
