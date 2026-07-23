package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualReaderCropPolicyTest {

    private final VisualReaderCropPolicy policy = new VisualReaderCropPolicy();
    private final UUID evidence = UUID.randomUUID();

    @Test
    void expands_a_small_icon_group_into_a_player_readable_viewport() {
        LocatedRegion icon = region("回合顺序图标", "一个箭头图标连接两个行动", 40, 50, 40, 40);

        LocatedRegion expanded = policy.expandIntoReaderViewport(icon);

        assertThat(policy.needsReaderViewport(icon)).isTrue();
        assertThat(policy.canExpandIntoReaderViewport(icon)).isTrue();
        assertThat(expanded.x()).isEqualTo(0);
        assertThat(expanded.y()).isEqualTo(10);
        assertThat(expanded.width()).isEqualTo(180);
        assertThat(expanded.height()).isEqualTo(120);
        assertThat(policy.isReadableForPlayer(expanded)).isTrue();
    }

    @Test
    void keeps_structured_references_but_rejects_a_text_only_rule_box() {
        LocatedRegion scoreTable = region("Score reference table", "printed labels form a score table", 100, 100, 280, 180);
        LocatedRegion ruleBox = region("文字说明", "规则框里只有文字", 100, 100, 280, 180);

        assertThat(policy.isUsefulPlayerVisual(scoreTable)).isTrue();
        assertThat(policy.isUsefulPlayerVisual(ruleBox)).isFalse();
    }

    @Test
    void rejects_whole_pages_and_marks_unusable_persisted_score_strips_for_replacement() {
        LocatedRegion wholePage = region("完整页面", "图中有一张卡牌", 0, 0, 1_000, 1_000);
        VisualFocus narrowScoreExample = new VisualFocus(2, "计分示例", 100, 100, 300, 500);
        VisualFocus oversized = new VisualFocus(2, "组件布局", 0, 0, 800, 800);

        assertThat(policy.isCompactReaderCrop(wholePage)).isFalse();
        assertThat(policy.needsTighterReaderCrop(narrowScoreExample)).isTrue();
        assertThat(policy.needsTighterReaderCrop(oversized)).isTrue();
    }

    @Test
    void keeps_candidate_and_duplicate_geometry_checks_independent_of_model_work() {
        LocatedRegion region = region("行动箭头", "箭头指向下一个行动", 180, 180, 200, 160);
        var matchingCandidate = new VisualRegionCandidateSelector.Candidate(
                2, new Rectangle(100, 100, 300, 300), "图中行动箭头");
        var otherPageCandidate = new VisualRegionCandidateSelector.Candidate(
                3, new Rectangle(100, 100, 300, 300), "另一页");
        VisualFocus sameViewport = new VisualFocus(2, "行动", 190, 190, 180, 150);
        VisualFocus differentViewport = new VisualFocus(2, "另一处", 600, 600, 120, 120);

        assertThat(policy.intersectsCandidate(region, List.of(matchingCandidate))).isTrue();
        assertThat(policy.intersectsCandidate(region, List.of(otherPageCandidate))).isFalse();
        assertThat(policy.overlapsSubstantially(
                new VisualFocus(2, "行动", 180, 180, 200, 160), sameViewport)).isTrue();
        assertThat(policy.overlapsSubstantially(
                new VisualFocus(2, "行动", 180, 180, 200, 160), differentViewport)).isFalse();
    }

    private LocatedRegion region(String label, String observation, int x, int y, int width, int height) {
        return new LocatedRegion(2, label, observation, x, y, width, height, List.of(evidence), List.of(1));
    }
}
