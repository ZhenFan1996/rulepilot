package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualReaderCropPolicyTest {

    private final VisualReaderCropPolicy policy = new VisualReaderCropPolicy();
    private final UUID evidence = UUID.randomUUID();

    @Test
    void keepsApplicationOwnedSmallGeometryExactInsteadOfInventingSurroundingContext() {
        LocatedRegion icon = region("回合顺序图标", "一个箭头图标连接两个行动", 40, 50, 40, 40);
        LocatedRegion unreadableFragment = region("碎片", "像素区域过小", 40, 50, 31, 31);

        assertThat(policy.isReadableForPlayer(icon)).isTrue();
        assertThat(icon.x()).isEqualTo(40);
        assertThat(icon.y()).isEqualTo(50);
        assertThat(icon.width()).isEqualTo(40);
        assertThat(icon.height()).isEqualTo(40);
        assertThat(policy.isReadableForPlayer(unreadableFragment)).isFalse();
    }

    @Test
    void leavesSemanticVisualUsefulnessToTheLocatorAndRejectsOnlyClaimConflict() {
        LocatedRegion scoreTable = region("Score reference table", "printed labels form a score table", 100, 100, 280, 180);
        LocatedRegion ruleBox = region("文字说明", "规则框里只有文字", 100, 100, 280, 180);
        LocatedRegion contradicted = new LocatedRegion(
                2,
                "冲突区域",
                "视觉模型明确标记该区域与被讲解主张冲突",
                100,
                100,
                280,
                180,
                List.of(evidence),
                List.of(1),
                true);

        assertThat(policy.isUsefulPlayerVisual(scoreTable)).isTrue();
        assertThat(policy.isUsefulPlayerVisual(ruleBox)).isTrue();
        assertThat(policy.isUsefulPlayerVisual(contradicted)).isFalse();
    }

    @Test
    void treatsFullPageAsATypedSourceInsteadOfRejectingItByArea() {
        LocatedRegion wholePage = new LocatedRegion(
                2,
                "完整流程图",
                "整页是一张由箭头连接的连续流程图",
                0,
                0,
                1_000,
                1_000,
                List.of(evidence),
                List.of(1),
                false,
                VisualSourceKind.FULL_PAGE);

        assertThat(policy.isReadableForPlayer(wholePage)).isTrue();
        assertThat(policy.isUsefulPlayerVisual(wholePage)).isTrue();
    }

    @Test
    void keeps_candidate_and_duplicate_geometry_checks_independent_of_model_work() {
        LocatedRegion region = region("行动箭头", "箭头指向下一个行动", 100, 100, 300, 300);
        LocatedRegion overlappingButAltered = region("行动箭头", "箭头指向下一个行动", 180, 180, 200, 160);
        var matchingCandidate = new VisualRegionCandidateSelector.Candidate(
                "matching_1", 2, new Rectangle(100, 100, 300, 300), VisualSourceKind.PAGE_REGION);
        var otherPageCandidate = new VisualRegionCandidateSelector.Candidate(
                "other_page_1", 3, new Rectangle(100, 100, 300, 300), VisualSourceKind.PAGE_REGION);
        VisualFocus sameViewport = new VisualFocus(2, "行动", 190, 190, 180, 150);
        VisualFocus differentViewport = new VisualFocus(2, "另一处", 600, 600, 120, 120);

        assertThat(policy.matchesCandidate(region, List.of(matchingCandidate))).isTrue();
        assertThat(policy.matchesCandidate(overlappingButAltered, List.of(matchingCandidate))).isFalse();
        assertThat(policy.matchesCandidate(region, List.of(otherPageCandidate))).isFalse();
        assertThat(policy.overlapsSubstantially(
                new VisualFocus(2, "行动", 180, 180, 200, 160), sameViewport)).isTrue();
        assertThat(policy.overlapsSubstantially(
                new VisualFocus(2, "行动", 180, 180, 200, 160), differentViewport)).isFalse();
    }

    private LocatedRegion region(String label, String observation, int x, int y, int width, int height) {
        return new LocatedRegion(2, label, observation, x, y, width, height, List.of(evidence), List.of(1));
    }
}
