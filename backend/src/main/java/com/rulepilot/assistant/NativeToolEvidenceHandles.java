package com.rulepilot.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Extracts validated evidence identities from exact-page observations. */
public final class NativeToolEvidenceHandles {

    private NativeToolEvidenceHandles() {}

    /** Returns every exact-page observation batch in newest-first order without trusting its page metadata. */
    public static List<Set<UUID>> exactPageObservationGroups(NativeToolAgent.RunResult result) {
        if (result == null) throw new IllegalArgumentException("native page observation result is required");
        java.util.ArrayList<Set<UUID>> groups = new java.util.ArrayList<>();
        List<NativeToolAgent.ObservationRecord> observations = result.observations();
        for (int index = observations.size() - 1; index >= 0; index--) {
            var record = observations.get(index);
            if (!"read_rule_pages".equals(record.toolName())
                    || record.observation().status() == NativeAgentTool.ObservationStatus.ERROR) continue;
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            add(record, ids);
            if (!ids.isEmpty()) groups.add(ordered(ids));
        }
        return List.copyOf(groups);
    }

    private static void add(NativeToolAgent.ObservationRecord record, LinkedHashSet<UUID> ids) {
        addValues(record.observation().data().get("evidence"), ids);
    }

    private static void addValues(Object evidence, LinkedHashSet<UUID> ids) {
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
        }
    }

    private static Set<UUID> ordered(LinkedHashSet<UUID> ids) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }
}
