package com.rulepilot.teaching.application;

import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import java.util.Locale;

/** Rejects only clear visual-to-rule category errors after cited evidence and model step binding have passed. */
final class VisualStepRelevancePolicy {

    boolean directlyIllustrates(LessonStep step, LocatedRegion region) {
        String heading = normalize(step.heading());
        String description = normalize(region.visibleDescription());
        String observation = normalize(region.label() + " " + description);
        if (isOperationalStep(heading) && isStaticSetupOverview(description) && !showsOperation(description)) {
            return false;
        }
        if (mentionsPlayerBoard(heading)) {
            return containsAny(observation, "玩家板", "个人板", "棋盘", "网格", "board", "grid", "town");
        }
        if (mentionsStartingActor(heading)) {
            return containsAny(observation, "主建筑师", "起始玩家", "锤", "标记", "master builder", "first player", "hammer", "marker");
        }
        if (mentionsTieResolution(heading)) {
            return containsAny(
                    observation,
                    "平局",
                    "同分",
                    "胜者",
                    "获胜",
                    "赢家",
                    "tie",
                    "winner",
                    "winning",
                    "hand",
                    "手牌",
                    "情绪卡",
                    "emotion card");
        }
        if (mentionsEndGame(heading)) {
            return containsAny(observation, "结束", "终局", "最后", "game over", "end of game", "final");
        }
        return true;
    }

    private boolean isOperationalStep(String heading) {
        return containsAny(heading, "放置", "移动", "移除", "place", "move", "remove");
    }

    private boolean isStaticSetupOverview(String description) {
        return containsAny(description, "初始设置", "初始布局", "组件总览", "组件摆放", "setup overview", "component overview", "component layout");
    }

    private boolean showsOperation(String description) {
        return containsAny(description, "已放置", "放入", "放在", "位于格", "箭头", "移除", "placed", "placement", "arrow", "removed");
    }

    private boolean mentionsPlayerBoard(String heading) {
        return containsAny(heading, "玩家板", "个人板", "player board", "player mat");
    }

    private boolean mentionsStartingActor(String heading) {
        return containsAny(heading, "主建筑师", "起始玩家", "master builder", "first player");
    }

    private boolean mentionsTieResolution(String heading) {
        return containsAny(heading, "平局", "同分", "tie-break", "tiebreak");
    }

    private boolean mentionsEndGame(String heading) {
        return containsAny(heading, "结束", "终局", "game over", "end of game");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... tokens) {
        return java.util.Arrays.stream(tokens).anyMatch(value::contains);
    }
}
