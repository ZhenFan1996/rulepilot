package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.RuleStructureCatalog.SectionView;
import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonQualityEvaluatorTest {

    @Test
    void blocksIncompleteLessonsWithoutTreatingUnknownScopeAsPassed() {
        var structure = new StructureView(
                List.of(
                        new SectionView("OBJECTIVE", "目标", true, "Win with points.", List.of(1)),
                        new SectionView("SETUP", "Setup", true, "Deal three cards.", List.of(2)),
                        new SectionView("SCORING", "计分", true, "Each coin scores one point.", List.of(8))),
                3,
                9);
        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), 4, 3, 30, "player", structure);
        var lesson = new IllustratedLessonFactory().create(plan, structure);

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(report.score()).isEqualTo(33);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.CITATION_SUPPORT)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.PASS);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.PLAYER_COUNT_SCOPE)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.NOT_EVALUATED);
    }
}
