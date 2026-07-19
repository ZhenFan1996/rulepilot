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
        assertThat(pacing.get(6).durationSeconds()).isGreaterThan(pacing.get(3).durationSeconds());
        assertThat(pacing.get(3).durationSeconds()).isGreaterThan(pacing.get(2).durationSeconds());
        assertThat(pacing.get(8).durationSeconds()).isGreaterThan(pacing.get(7).durationSeconds());
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
                        index + 1,
                        types.get(index).name(),
                        types.get(index).name(),
                        "Explain topic",
                        types.get(index).required(),
                        List.of(types.get(index).name()),
                        List.of(tag(types.get(index)))))
                .toList();
        return new TeachingPlan(
                UUID.randomUUID(), UUID.randomUUID(), 4, 2, durationMinutes, "Game", "Premise", sections, "player", Instant.now());
    }

    private String tag(TeachingSectionType type) {
        return switch (type) {
            case SETUP -> "setup";
            case ACTIONS, ROUND_STRUCTURE, PHASES -> "core_loop";
            case END_CONDITIONS -> "end";
            case SCORING -> "scoring";
            default -> type.name().toLowerCase();
        };
    }
}
