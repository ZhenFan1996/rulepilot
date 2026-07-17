package com.rulepilot.teaching.application;

import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class TeachingPlanFactory {

    public TeachingPlan create(
            UUID documentVersionId,
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            String createdBy,
            StructureView structure) {
        Map<String, com.rulepilot.ingestion.RuleStructureCatalog.SectionView> evidence = structure.sections().stream()
                .collect(Collectors.toMap(section -> section.type(), Function.identity()));
        List<TeachingSectionType> selected = new ArrayList<>(Arrays.stream(TeachingSectionType.values())
                .filter(TeachingSectionType::required)
                .toList());
        if (beginnerCount > 0 && durationMinutes >= 20) {
            selected.add(TeachingSectionType.FIRST_ROUND_PRACTICE);
        }
        if (durationMinutes >= 30) {
            selected.add(TeachingSectionType.COMMON_MISTAKES);
        }
        if (beginnerCount > 0 || durationMinutes >= 15) {
            selected.add(TeachingSectionType.RECAP);
        }

        List<PlannedSection> sections = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            TeachingSectionType type = selected.get(index);
            var source = evidence.get(type.name());
            List<Integer> sourcePages = type.required()
                    ? source == null ? List.of() : source.pageNumbers()
                    : type.dependencies().stream()
                            .map(dependency -> evidence.get(dependency.name()))
                            .filter(java.util.Objects::nonNull)
                            .flatMap(section -> section.pageNumbers().stream())
                            .distinct()
                            .toList();
            boolean evidenceAvailable = type.required()
                    ? source != null && source.present()
                    : type.dependencies().stream()
                            .map(dependency -> evidence.get(dependency.name()))
                            .allMatch(section -> section != null && section.present());
            sections.add(new PlannedSection(
                    index + 1,
                    type,
                    type.required(),
                    evidenceAvailable,
                    sourcePages,
                    type.dependencies()));
        }
        return new TeachingPlan(
                UUID.randomUUID(),
                documentVersionId,
                playerCount,
                beginnerCount,
                durationMinutes,
                sections,
                createdBy,
                Instant.now());
    }
}
