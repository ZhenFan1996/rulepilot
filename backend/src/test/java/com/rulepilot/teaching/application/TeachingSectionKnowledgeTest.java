package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.TeachingSectionType;
import org.junit.jupiter.api.Test;

class TeachingSectionKnowledgeTest {

    @Test
    void providesSpecificGuidanceForEveryTeachingSection() {
        for (TeachingSectionType type : TeachingSectionType.values()) {
            var guidance = TeachingSectionKnowledge.forSection(type);
            assertThat(guidance.objective()).as(type.name()).isNotBlank();
            assertThat(guidance.coverageChecklist()).as(type.name()).isNotBlank();
        }

        assertThat(TeachingSectionKnowledge.forSection(TeachingSectionType.SETUP).coverageChecklist())
                .contains("play area", "starting player");
        assertThat(TeachingSectionKnowledge.forSection(TeachingSectionType.SCORING).coverageChecklist())
                .contains("bonuses", "penalties", "winner comparison");
    }
}
