package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualStepRelevancePolicyTest {

    private final VisualStepRelevancePolicy policy = new VisualStepRelevancePolicy();
    private final UUID evidence = UUID.randomUUID();

    @Test
    void rejects_a_static_setup_overview_for_an_operational_rule_but_keeps_an_actual_placement() {
        LessonStep step = step("把标记移动到行动格");

        assertThat(policy.directlyIllustrates(step, region("初始布局", "桌面展示组件的初始设置状态"))).isFalse();
        assertThat(policy.directlyIllustrates(step, region("移动示例", "标记已放在行动格，箭头指向下一格"))).isTrue();
    }

    @Test
    void keeps_board_and_starting_actor_checks_tied_to_the_visual_observation() {
        assertThat(policy.directlyIllustrates(step("每位玩家拿取玩家板"), region("资源图例", "彩色方块对应资源名称"))).isFalse();
        assertThat(policy.directlyIllustrates(step("每位玩家拿取玩家板"), region("玩家板网格", "个人棋盘是 4×4 网格"))).isTrue();
        assertThat(policy.directlyIllustrates(step("确定起始玩家"), region("轮次表", "按顺序行动"))).isFalse();
        assertThat(policy.directlyIllustrates(step("确定起始玩家"), region("锤子标记", "锤子标记表示起始玩家"))).isTrue();
    }

    @Test
    void distinguishes_tie_resolution_and_end_game_from_neighbouring_scoring_content() {
        assertThat(policy.directlyIllustrates(step("平局时怎么分胜负？"), region("计分表", "列出每项 0、2、4 分"))).isFalse();
        assertThat(policy.directlyIllustrates(step("平局时怎么分胜负？"), region("赢家比较", "同分时手牌更多的玩家获胜"))).isTrue();
        assertThat(policy.directlyIllustrates(step("游戏何时结束"), region("资源图标", "资源图标名称对照"))).isFalse();
        assertThat(policy.directlyIllustrates(step("游戏何时结束"), region("最终结算", "游戏结束后进行最后计分"))).isTrue();
    }

    @Test
    void does_not_reject_an_unclassified_rule_only_for_lacking_keyword_overlap() {
        assertThat(policy.directlyIllustrates(step("支付建造费用"), region("木材与石头", "两种资源放在建筑旁"))).isTrue();
    }

    private LessonStep step(String heading) {
        return new LessonStep(1, heading, TeachingMove.DO, "按规则完成这一步。", List.of(2), List.of(evidence));
    }

    private LocatedRegion region(String label, String observation) {
        return new LocatedRegion(2, label, observation, 100, 100, 240, 160, List.of(evidence), List.of(1));
    }
}
