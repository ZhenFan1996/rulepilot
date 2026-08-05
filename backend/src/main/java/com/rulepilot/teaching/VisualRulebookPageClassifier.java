package com.rulepilot.teaching;

import java.util.Locale;
import java.util.regex.Pattern;

/** Classifies page-local visual facts without relying on a particular rulebook's vocabulary. */
public final class VisualRulebookPageClassifier {

    private static final Pattern EXPLICIT_NON_GAMEPLAY = Pattern.compile(
            "(?iu)(?:\\b(?:credits?|copyright|acknowledg(?:e)?ments?|table\\s+of\\s+contents|index|"
                    + "advertisement|non-gameplay(?:\\s+(?:material|page|rule))?)\\b"
                    + "|致谢|鸣谢|版权|目录|索引|广告|宣传页|非游戏规则|非游戏玩法|仅为收纳或组装说明)");
    private static final Pattern EXPLICIT_COVER = Pattern.compile(
            "(?iu)(?:\\b(?:front\\s+cover|back\\s+cover|rulebook\\s+cover|cover\\s+art(?:work)?|title\\s+page|"
                    + "visual\\s+cover)\\b|封面|封底|书名页|仅为封面设计|仅作为视觉封面)");
    private static final Pattern GAMEPLAY_EVIDENCE = Pattern.compile(
            "(?iu)(?:\\b(?:components?|contents|objective|goal|setup|set\\s+up|starting\\s+resources?|"
                    + "how\\s+to\\s+play|game\\s+overview|players?\\s+(?:must|may|can|take|choose|place|draw|gain)|"
                    + "turns?|rounds?|phases?|actions?|move|deploy|discard|pay|cost|gain|draw|score|scoring|points?|"
                    + "winner|victory|tie|end\\s+of\\s+(?:the\\s+)?game|game\\s+ends?|rule)\\b"
                    + "|组件|配件|内容物|游戏目标|获胜目标|设置|准备|起始资源|游戏概览|玩法|玩家(?:必须|可以|选择|拿取|放置|抽取|获得)|"
                    + "回合|轮次|阶段|行动|移动|部署|弃置|支付|花费|获得|抽取|计分|分数|胜者|获胜|胜利|平局|游戏结束|终局|规则)");

    private VisualRulebookPageClassifier() {}

    /**
     * A visual page is substantive only when it can own a player-facing rule. An identity-only first page is front
     * matter even if the visual model did not explicitly call it a cover; a compact rules sheet on page one remains
     * substantive as soon as its page-local facts contain concrete gameplay evidence.
     */
    public static boolean isSubstantive(int pageNumber, String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (EXPLICIT_NON_GAMEPLAY.matcher(normalized).find()) return false;
        if (normalized.contains("storage or assembly instructions")
                && (normalized.contains("not gameplay")
                        || normalized.contains("only for storage")
                        || normalized.contains("this page is"))) {
            return false;
        }
        boolean gameplay = GAMEPLAY_EVIDENCE.matcher(normalized).find();
        if (EXPLICIT_COVER.matcher(normalized).find()) return false;
        return pageNumber != 1 || gameplay;
    }
}
