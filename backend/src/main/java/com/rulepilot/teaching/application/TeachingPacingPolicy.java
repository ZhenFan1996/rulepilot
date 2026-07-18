package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class TeachingPacingPolicy {

    private static final int MIN_SECTION_SECONDS = 10;
    private static final Map<TeachingSectionType, Integer> WEIGHTS = Map.ofEntries(
            Map.entry(TeachingSectionType.OBJECTIVE, 7),
            Map.entry(TeachingSectionType.COMPONENTS, 8),
            Map.entry(TeachingSectionType.SETUP, 14),
            Map.entry(TeachingSectionType.ROUND_STRUCTURE, 9),
            Map.entry(TeachingSectionType.PHASES, 9),
            Map.entry(TeachingSectionType.ACTIONS, 18),
            Map.entry(TeachingSectionType.END_CONDITIONS, 7),
            Map.entry(TeachingSectionType.SCORING, 12),
            Map.entry(TeachingSectionType.TIE_BREAKERS, 6),
            Map.entry(TeachingSectionType.FIRST_ROUND_PRACTICE, 14),
            Map.entry(TeachingSectionType.COMMON_MISTAKES, 8),
            Map.entry(TeachingSectionType.RECAP, 7));

    private TeachingPacingPolicy() {}

    public static Map<TeachingSectionType, SectionPacing> allocate(TeachingPlan plan) {
        if (plan == null || plan.sections().isEmpty()) {
            throw new IllegalArgumentException("teaching plan with sections is required");
        }
        int totalSeconds = Math.multiplyExact(plan.durationMinutes(), 60);
        int minimumSeconds = Math.multiplyExact(plan.sections().size(), MIN_SECTION_SECONDS);
        if (totalSeconds < minimumSeconds) {
            throw new IllegalArgumentException("lesson duration cannot cover every planned section");
        }
        int distributable = totalSeconds - minimumSeconds;
        int totalWeight = plan.sections().stream().mapToInt(section -> weight(section.type())).sum();
        EnumMap<TeachingSectionType, MutableAllocation> allocations = new EnumMap<>(TeachingSectionType.class);
        int allocatedSeconds = 0;
        for (var section : plan.sections()) {
            long weightedSeconds = (long) distributable * weight(section.type());
            int seconds = MIN_SECTION_SECONDS + (int) (weightedSeconds / totalWeight);
            allocations.put(
                    section.type(),
                    new MutableAllocation(section.position(), seconds, weightedSeconds % totalWeight));
            allocatedSeconds += seconds;
        }

        List<MutableAllocation> byRemainder = new ArrayList<>(allocations.values());
        byRemainder.sort(Comparator.comparingLong(MutableAllocation::remainder)
                .reversed()
                .thenComparingInt(MutableAllocation::position));
        for (int index = 0; allocatedSeconds < totalSeconds; index++, allocatedSeconds++) {
            byRemainder.get(index).seconds++;
        }

        EnumMap<TeachingSectionType, SectionPacing> result = new EnumMap<>(TeachingSectionType.class);
        allocations.forEach((type, allocation) -> result.put(
                type, new SectionPacing(allocation.seconds, maxSteps(allocation.seconds))));
        return Map.copyOf(result);
    }

    private static int weight(TeachingSectionType type) {
        return WEIGHTS.getOrDefault(type, 1);
    }

    private static int maxSteps(int seconds) {
        if (seconds <= 30) {
            return 1;
        }
        if (seconds <= 60) {
            return 2;
        }
        if (seconds <= 90) {
            return 3;
        }
        if (seconds <= 120) {
            return 4;
        }
        if (seconds <= 180) {
            return 5;
        }
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

        int position() {
            return position;
        }

        long remainder() {
            return remainder;
        }
    }
}
