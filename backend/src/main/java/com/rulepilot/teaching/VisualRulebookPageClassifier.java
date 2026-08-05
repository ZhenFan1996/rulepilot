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
    private static final Pattern IDENTITY_OR_DECORATION = Pattern.compile(
            "(?iu)(?:\\b(?:logos?|publisher|published\\s+by|designer|designed\\s+by|artist|illustrated\\s+by|"
                    + "awards?|finalist|nominee|decorative|decoration|artwork|background\\s+pattern|brand\\s+mark)\\b"
                    + "|出版商|发行商|设计者|设计师|美术|插画|奖项|入围|提名|装饰|纹样|背景图案|品牌标志|徽标|标识)");
    private static final Pattern CONCRETE_GAMEPLAY_FACT = Pattern.compile(
            "(?iu)(?:\\b(?:components?\\s+(?:list|include|are)|contents\\s*:|objective\\s*:|goal\\s*:|"
                    + "setup|set\\s+up|starting\\s+resources?|how\\s+to\\s+play|game\\s+overview|on\\s+your\\s+turn|"
                    + "during\\s+your\\s+turn|each\\s+player|players?\\s+(?:must|may|can|take|choose|place|draw|gain|score)|"
                    + "game\\s+(?:contains|includes|ends?)|end\\s+of\\s+(?:the\\s+)?game|highest\\s+score\\s+wins?)\\b"
                    + "|组件(?:清单|包括|包含|为)|内容物|游戏(?:包含|包括|目标|概览|结束)|获胜目标|"
                    + "设置|准备步骤|起始资源|玩法|你的回合|回合中|每位玩家|玩家(?:必须|可以|选择|拿取|放置|抽取|获得|计分)|"
                    + "当.{0,40}(?:结束|获得|拿取|放置|弃置|计分)|得分最高者获胜)");

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
        if (EXPLICIT_COVER.matcher(normalized).find()) return false;
        String facts = visibleFacts(normalized);
        boolean concreteGameplayFact = CONCRETE_GAMEPLAY_FACT.matcher(facts).find();
        if (IDENTITY_OR_DECORATION.matcher(facts).find() && !concreteGameplayFact) return false;
        return pageNumber != 1 || concreteGameplayFact;
    }

    private static String visibleFacts(String catalogText) {
        int facts = catalogText.indexOf("visible facts:");
        if (facts < 0) return catalogText;
        int start = facts + "visible facts:".length();
        int keywords = catalogText.indexOf("\nkeywords:", start);
        return keywords < 0 ? catalogText.substring(start) : catalogText.substring(start, keywords);
    }
}
