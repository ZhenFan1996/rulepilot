package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogPolicyTest {

    @Test
    void keepsEveryVisualPageIndependent() {
        assertThat(VisualRulebookCatalogPolicy.singlePageBatches(List.of(2, 5, 7)))
                .containsExactly(List.of(2), List.of(5), List.of(7));
    }

    @Test
    void retriesEmptyIconInventoryOnlyForAnIconBearingAnchor() {
        PageSummary scoringReference = summary(
                new VisualAnchor("table", "Scoring reference", "A structured reference.", 100, 100, 300, 300));
        PageSummary labeledIconGroup = summary(
                new VisualAnchor("labeled icon group", "Components", "Several labeled shapes.", 100, 100, 300, 300));
        PageSummary cover = summary(
                new VisualAnchor("title block", "RULEBOOK", "Cover title and illustration.", 100, 100, 300, 300));
        PageSummary setupProse =
                summary(new VisualAnchor("setup list", "Steps A-D", "Numbered setup prose.", 100, 100, 300, 300));

        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(scoringReference)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(labeledIconGroup)).isTrue();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(cover)).isFalse();
        assertThat(VisualRulebookCatalogPolicy.needsIconTileFallback(setupProse)).isFalse();
    }

    @Test
    void publishesCompactSymbolsButRejectsCalloutsComponentsAndRowSizedRegions() {
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("leaf", "叶子图标"), 100, 100, 24, 28))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon(
                                "carrot",
                                "胡萝卜图标",
                                "卡片角落白色圆圈中的胡萝卜插图。"),
                        100,
                        100,
                        24,
                        28))
                .isTrue();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("point card", "积分卡", "Whole card with a scoring condition."), 100, 100, 88, 170))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("A", "A"), 100, 100, 24, 24))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("objective-i", "Objective", "Black uppercase letter I inside a circle."),
                        100,
                        100,
                        24,
                        24))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("check", "对勾", "Green checkmark next to text labels."), 100, 100, 24, 24))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("rainbow", "彩虹", "Small arc next to text mentioning a button."), 100, 100, 24, 24))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("cat illustration", "猫插图"), 100, 100, 64, 64))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("publisher logo", "出版社徽标", "Red shield with white letters."), 100, 100, 64, 64))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("adjacency example", "同色相邻示例"), 100, 100, 177, 35))
                .isFalse();
        assertThat(VisualRulebookCatalogPolicy.publishableLocalizedIcon(
                        icon("leaf row", "叶子图案"), 100, 100, 860, 50))
                .isFalse();
    }

    private static PageSummary summary(VisualAnchor anchor) {
        return new PageSummary(
                1, "VISIBLE TEXT", "Visible page facts.", List.of("reference"), List.of(anchor), List.of(), true);
    }

    private static IconOccurrence icon(String groupKey, String name) {
        return icon(groupKey, name, "A compact visible mark.");
    }

    private static IconOccurrence icon(String groupKey, String name, String visualDescription) {
        return new IconOccurrence(
                groupKey,
                name,
                visualDescription,
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                100,
                100,
                20,
                20);
    }
}
