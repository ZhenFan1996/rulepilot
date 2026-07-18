package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPacingPolicyTest {

    @Test
    void allocatesTheExactDurationAndPrioritizesCoreProcedures() {
        TeachingPlan plan = plan(10, requiredSections());

        var pacing = TeachingPacingPolicy.allocate(plan);

        assertThat(pacing.values()).satisfies(values -> assertThat(values.stream()
                        .mapToInt(TeachingPacingPolicy.SectionPacing::durationSeconds)
                        .sum())
                .isEqualTo(600));
        assertThat(pacing.get(TeachingSectionType.ACTIONS).durationSeconds())
                .isGreaterThan(pacing.get(TeachingSectionType.SETUP).durationSeconds());
        assertThat(pacing.get(TeachingSectionType.SETUP).durationSeconds())
                .isGreaterThan(pacing.get(TeachingSectionType.COMPONENTS).durationSeconds());
        assertThat(pacing.get(TeachingSectionType.SCORING).durationSeconds())
                .isGreaterThan(pacing.get(TeachingSectionType.END_CONDITIONS).durationSeconds());
    }

    @Test
    void keepsEverySelectedSectionAndBoundsStepDensityForACompressedLesson() {
        List<TeachingSectionType> allSections = Arrays.asList(TeachingSectionType.values());

        var pacing = TeachingPacingPolicy.allocate(plan(2, allSections));

        assertThat(pacing).hasSize(allSections.size());
        assertThat(pacing.values()).allSatisfy(section -> {
            assertThat(section.durationSeconds()).isEqualTo(10);
            assertThat(section.maxSteps()).isEqualTo(1);
        });
    }

    private List<TeachingSectionType> requiredSections() {
        return Arrays.stream(TeachingSectionType.values()).filter(TeachingSectionType::required).toList();
    }

    private TeachingPlan plan(int durationMinutes, List<TeachingSectionType> types) {
        List<PlannedSection> sections = java.util.stream.IntStream.range(0, types.size())
                .mapToObj(index -> new PlannedSection(
                        index + 1, types.get(index), types.get(index).required(), true, List.of(1), List.of()))
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(), UUID.randomUUID(), 4, 2, durationMinutes, sections, "player", Instant.now());
    }
}
