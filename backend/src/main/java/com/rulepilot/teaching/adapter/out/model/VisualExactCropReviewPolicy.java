package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.VisualRegionLocator.Claim;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Keeps Qwen's optional crop second-opinion budget for visually confusable rule steps. */
final class VisualExactCropReviewPolicy {

    private VisualExactCropReviewPolicy() {}

    static boolean qwenNeedsExactCropReview(List<Claim> claims) {
        String text = claims.stream()
                .map(Claim::text)
                .map(VisualExactCropReviewPolicy::exactStepHeading)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        return text.contains("卡牌")
                || text.contains("玩家卡")
                || text.contains("组件")
                || text.contains("令牌")
                || text.contains("标记")
                || text.contains("图标")
                || text.contains("统治卡")
                || text.contains("打出")
                || text.contains("激活")
                || text.contains("使用")
                || text.contains("阵营")
                || text.contains("计分")
                || text.contains("得分")
                || text.contains("分数")
                || text.contains("胜利")
                || text.contains("结束")
                || text.contains("平局")
                || text.contains("card")
                || text.contains("component")
                || text.contains("token")
                || text.contains("marker")
                || text.contains("icon")
                || text.contains("play")
                || text.contains("activate")
                || text.contains("faction")
                || text.contains("score")
                || text.contains("point")
                || text.contains("win")
                || text.contains("end")
                || text.contains("tie");
    }

    private static String exactStepHeading(String claim) {
        if (claim == null || claim.isBlank()) return "high-risk-unreadable-heading";
        int opening = claim.indexOf('（');
        int closing = claim.indexOf('）', opening + 1);
        if (opening >= 0 && closing > opening + 1) return claim.substring(opening + 1, closing);
        opening = claim.indexOf('(');
        closing = claim.indexOf(')', opening + 1);
        return opening >= 0 && closing > opening + 1
                ? claim.substring(opening + 1, closing)
                : "high-risk-unreadable-heading";
    }
}
