package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentProcessing.ExtractedPage;
import com.rulepilot.ingestion.domain.LessonRuleSectionType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleStructureClassifierTest {

    private final RuleStructureClassifier classifier = new RuleStructureClassifier();

    @Test
    void classifiesCitedSectionsAndLeavesUnsupportedSectionsMissing() {
        var sections = classifier.classify(List.of(
                new ExtractedPage(2, "Setup: Give each player three cards and place the board centrally."),
                new ExtractedPage(8, "Scoring: Each coin is worth one victory point.")));

        assertThat(sections).extracting(section -> section.type()).containsExactlyInAnyOrder(
                LessonRuleSectionType.SETUP, LessonRuleSectionType.SCORING);
        assertThat(sections.stream()
                        .filter(section -> section.type() == LessonRuleSectionType.SETUP)
                        .findFirst()
                        .orElseThrow()
                        .pageNumbers())
                .containsExactly(2);
    }
}
