package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeachingRunWorkloadPolicyTest {

    private final TeachingRunWorkloadPolicy policy = new TeachingRunWorkloadPolicy();

    @Test
    void sizesAdmissionFromTheOrdinaryOneCandidatePerSectionPath() {
        var demand = policy.demand(plan(19, true));

        // Candidate correction and additional candidate batches are intentionally absent from this source-window
        // estimate: the durable token/deadline owner governs useful recovery instead of imposing an attempt count.
        assertThat(demand.estimatedModelCalls()).isEqualTo(57);
    }

    @Test
    void doesNotInflateACompactTextLessonToAnUnrelatedConfiguredFloor() {
        var demand = policy.demand(plan(3, false));

        assertThat(demand.estimatedModelCalls()).isEqualTo(3);
    }

    @Test
    void derivesACapacityEstimateForALargerUnlikePlanWithoutAFixedPlanCeiling() {
        var demand = policy.demand(plan(30, true));

        assertThat(demand.estimatedModelCalls()).isEqualTo(90);
    }

    @Test
    void countsEveryPageBindingInsteadOfAssumingOneVisualCallPerSection() {
        List<List<Integer>> pageBindings = List.of(
                List.of(2, 3), List.of(4), List.of(5), List.of(6, 7), List.of(7),
                List.of(8), List.of(8, 9), List.of(9, 10), List.of(10), List.of(11),
                List.of(12), List.of(13), List.of(14), List.of(15), List.of(16),
                List.of(17), List.of(18, 20, 21), List.of(19), List.of(22));
        TeachingPlan plan = plan(pageBindings);
        int visualCalls = TeachingVisualEvidenceResolver.estimatedCatalogModelCalls(plan);

        assertThat(visualCalls).isEqualTo(21);
        assertThat(policy.demand(plan))
                .isEqualTo(new com.rulepilot.assistant.AssistantRuns.WorkloadDemand(59));
    }

    @Test
    void estimatesVisualWorkFromActualSourcePageWindowsWithoutCreatingACallLimit() {
        TeachingPlan plan = plan(2, false);

        assertThat(VisualLessonEnricher.estimatedTeachingRunModelCalls(plan)).isZero();
        assertThat(policy.demand(plan).estimatedModelCalls()).isEqualTo(2);
    }

    private TeachingPlan plan(int sectionCount, boolean sourceBound) {
        List<List<Integer>> pageBindings = IntStream.rangeClosed(1, sectionCount)
                .mapToObj(position -> sourceBound ? List.of(position) : List.<Integer>of())
                .toList();
        return plan(pageBindings);
    }

    private TeachingPlan plan(List<List<Integer>> pageBindings) {
        List<PlannedSection> sections = IntStream.range(0, pageBindings.size())
                .mapToObj(position -> new PlannedSection(
                        position + 1,
                        "topic-" + (position + 1),
                        "Topic " + (position + 1),
                        "Teach one independently bounded relation.",
                        true,
                        !pageBindings.get(position).isEmpty(),
                        List.of("intent-a-" + position, "intent-b-" + position, "intent-c-" + position),
                        List.of("source_coverage"),
                        pageBindings.get(position)))
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Budget fixture",
                "A game-independent workload fixture.",
                sections,
                "player",
                Instant.now());
    }
}
