package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.RuleStructureCatalog.SectionView;
import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanFactoryTest {

    private final TeachingPlanFactory factory = new TeachingPlanFactory();

    @Test
    void keepsRequiredGapsAndAddsBeginnerSectionsWhenTimeAllows() {
        var structure = new StructureView(
                List.of(
                        new SectionView("OBJECTIVE", "目标", true, "Win with points", List.of(1)),
                        new SectionView("SETUP", "Setup", true, "Deal cards", List.of(2))),
                2,
                9);

        var plan = factory.create(UUID.randomUUID(), 4, 3, 30, "player", structure);

        assertThat(plan.sections().stream().filter(section -> section.required())).hasSize(9);
        assertThat(plan.sections()).extracting(section -> section.type()).contains(
                TeachingSectionType.FIRST_ROUND_PRACTICE,
                TeachingSectionType.COMMON_MISTAKES,
                TeachingSectionType.RECAP);
        assertThat(plan.complete()).isFalse();
        assertThat(plan.sections().stream()
                        .filter(section -> section.type() == TeachingSectionType.FIRST_ROUND_PRACTICE)
                        .findFirst()
                        .orElseThrow()
                        .evidenceAvailable())
                .isFalse();
        assertThat(plan.sections().stream()
                        .filter(section -> section.type() == TeachingSectionType.SETUP)
                        .findFirst()
                        .orElseThrow()
                        .sourcePages())
                .containsExactly(2);
    }
}
