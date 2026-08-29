package com.rulepilot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeToolAgent.ObservationRecord;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeToolEvidenceHandlesTest {

    @Test
    void extractsExactPageBatchesNewestFirstWithoutTrustingPageFields() {
        UUID earlier = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        RunResult result = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                3,
                3,
                List.of(
                        observation(1, "read_rule_pages", earlier),
                        observation(2, "search_rule_evidence", UUID.randomUUID()),
                        observation(3, "read_rule_pages", newer)));

        var groups = NativeToolEvidenceHandles.exactPageObservationGroups(result);

        assertThat(groups).containsExactly(java.util.Set.of(newer), java.util.Set.of(earlier));
    }

    @Test
    void ignoresMalformedAndNonPageObservationHandles() {
        ToolObservation malformed = ToolObservation.success(
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", List.of(Map.of("evidenceId", "not-a-uuid"))),
                1);
        RunResult result = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                1,
                1,
                List.of(new ObservationRecord(1, "read_rule_pages", "schema", malformed)));

        assertThat(NativeToolEvidenceHandles.exactPageObservationGroups(result)).isEmpty();
    }

    @Test
    void ignoresEvidenceHandlesCarriedByAnErrorPageObservation() {
        UUID untrusted = UUID.randomUUID();
        ToolObservation error = new ToolObservation(
                NativeAgentTool.ObservationStatus.ERROR,
                "PAGE_READ_REJECTED",
                Map.of("evidence", List.of(Map.of("evidenceId", untrusted.toString()))),
                1);
        RunResult result = new RunResult(
                RunStatus.FALLBACK,
                "unavailable",
                "EMPTY_MODEL_RESULT",
                1,
                1,
                List.of(new ObservationRecord(1, "read_rule_pages", "schema", error)));

        assertThat(NativeToolEvidenceHandles.exactPageObservationGroups(result)).isEmpty();
    }

    @Test
    void retainsEveryValidatedIdentityFromALargeExactPageObservation() {
        List<UUID> identities = java.util.stream.IntStream.range(0, 125)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        ToolObservation observation = ToolObservation.success(
                "PAGE_EVIDENCE_FOUND",
                Map.of("evidence", identities.stream()
                        .map(identity -> Map.of("evidenceId", identity.toString()))
                        .toList()),
                identities.size());
        RunResult result = new RunResult(
                RunStatus.COMPLETED,
                "EVIDENCE_READY",
                "MODEL_COMPLETED",
                1,
                1,
                List.of(new ObservationRecord(1, "read_rule_pages", "schema", observation)));

        assertThat(NativeToolEvidenceHandles.exactPageObservationGroups(result))
                .singleElement()
                .satisfies(group -> assertThat(group).containsExactlyElementsOf(identities));
    }

    private ObservationRecord observation(int iteration, String toolName, UUID evidenceId) {
        return new ObservationRecord(
                iteration,
                toolName,
                "schema",
                ToolObservation.success(
                        "EVIDENCE_FOUND",
                        Map.of("evidence", List.of(Map.of("evidenceId", evidenceId.toString()))),
                        1));
    }
}
