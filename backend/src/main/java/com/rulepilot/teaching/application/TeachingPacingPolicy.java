package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TeachingPacingPolicy {

    private static final int MIN_SECTION_SECONDS = 10;

    private TeachingPacingPolicy() {}

    public static Map<Integer, SectionPacing> allocate(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) {
            throw new IllegalArgumentException("teaching plan with sections is required");
        }
        int totalSeconds = Math.multiplyExact(plan.durationMinutes(), 60);
        int minimumSeconds = Math.multiplyExact(plan.sections().size(), MIN_SECTION_SECONDS);
        if (totalSeconds < minimumSeconds) {
            throw new IllegalArgumentException("lesson duration cannot cover every planned section");
        }
        int distributable = totalSeconds - minimumSeconds;
        int totalWeight = plan.sections().stream().mapToInt(TeachingPacingPolicy::weight).sum();
        Map<Integer, MutableAllocation> allocations = new LinkedHashMap<>();
        int allocatedSeconds = 0;
        for (var section : plan.sections()) {
            long weightedSeconds = (long) distributable * weight(section);
            int seconds = MIN_SECTION_SECONDS + (int) (weightedSeconds / totalWeight);
            allocations.put(section.position(), new MutableAllocation(section.position(), seconds, weightedSeconds % totalWeight));
            allocatedSeconds += seconds;
        }
        List<MutableAllocation> byRemainder = new ArrayList<>(allocations.values());
        byRemainder.sort(Comparator.comparingLong(MutableAllocation::remainder).reversed().thenComparingInt(MutableAllocation::position));
        for (int index = 0; allocatedSeconds < totalSeconds; index++, allocatedSeconds++) {
            byRemainder.get(index % byRemainder.size()).seconds++;
        }
        Map<Integer, SectionPacing> result = new LinkedHashMap<>();
        allocations.forEach((position, allocation) -> result.put(position, new SectionPacing(allocation.seconds, maxSteps(allocation.seconds))));
        return Map.copyOf(result);
    }

    private static int weight(TeachingPlan.PlannedSection section) {
        if (section.coverageTags().contains("setup")) return 14;
        if (section.coverageTags().contains("core_loop") || section.coverageTags().contains("actions")) return 18;
        if (section.coverageTags().contains("scoring")) return 12;
        if (section.coverageTags().contains("first_round")) return 14;
        return section.required() ? 8 : 5;
    }

    private static int maxSteps(int seconds) {
        if (seconds <= 30) return 1;
        if (seconds <= 60) return 2;
        if (seconds <= 90) return 3;
        if (seconds <= 120) return 4;
        if (seconds <= 180) return 5;
        return 6;
    }

    public record SectionPacing(int durationSeconds, int maxSteps) {
        public SectionPacing {
            if (durationSeconds < MIN_SECTION_SECONDS || maxSteps < 1 || maxSteps > 6) {
                throw new IllegalArgumentException("section pacing is invalid");
            }
        }
    }

    private static final class MutableAllocation {
        private final int position;
        private int seconds;
        private final long remainder;
        private MutableAllocation(int position, int seconds, long remainder) {
            this.position = position;
            this.seconds = seconds;
            this.remainder = remainder;
        }
        int position() { return position; }
        long remainder() { return remainder; }
    }
}
