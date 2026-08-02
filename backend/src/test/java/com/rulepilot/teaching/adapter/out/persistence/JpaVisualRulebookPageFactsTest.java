package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaVisualRulebookPageFactsTest {

    @Test
    void preservesExactPrintedIdentifiersForCandidateDisambiguation() {
        assertThat(JpaVisualRulebookPageFacts.printedIdentifiers("Compare A-01, B#02, and a-01"))
                .containsExactly("a-01", "b#02");
    }

    @Test
    void buildsBoundedPrefixOrQueryWithoutConversationalFiller() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "Is clearing three matching wildlife tokens optional and how often can you do this?"))
                .isEqualTo("clearing:* | three:* | matching:* | wildlife:* | token:* | optional:* | often:*");
    }

    @Test
    void searchTermsKeepsShortPrintedIdentifiersSplitByPunctuation() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "What does TT#02 or A-01 do?"))
                .contains("tt:*", "02:*", "a:*", "01:*");
    }

    @Test
    void extractsChineseFragmentsForImageFactMatching() {
        assertThat(JpaVisualRulebookPageFacts.cjkFragments("野生动物标记可以放在哪些板块上？"))
                .contains("野生", "动物", "标记", "可以", "放在", "板块")
                .doesNotContain("哪些");
    }

    @Test
    void samplesLongChineseQuestionsAcrossTheEntireQuestionInsteadOfOnlyThePrefix() {
        assertThat(JpaVisualRulebookPageFacts.cjkFragments(
                        "主动玩家掷出骰子后，其他被动玩家是否能从自己已部署卡牌领取红色奖励？"))
                .contains("主动", "被动", "红色", "奖励")
                .hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void normalizesPlacementWordsForPrintedRulebookTerms() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "Where can I place a Wildlife Token after placing it on Keystone Tiles?"))
                .isEqualTo("place:* | wildlife:* | token:* | after:* | keystone:* | tile:*");
    }

    @Test
    void preserves_visual_anchor_geometry_when_mapping_a_page_fact_to_storage() {
        var original = new PageFact(
                7,
                "Fox scoring",
                "狐狸图示旁有相邻的栖息地卡牌。",
                List.of("Fox", "scoring"),
                List.of(new VisualAnchor("score group", "Fox scoring", "狐狸卡牌与相邻卡牌的得分示例。", 90, 260, 280, 220)));

        var restored = new VisualRulebookPageFactEntity(UUID.randomUUID(), original).toDomain();

        assertThat(restored.visualAnchors()).containsExactlyElementsOf(original.visualAnchors());
    }

    @Test
    void preserves_icon_meaning_evidence_and_page_scan_completeness() {
        var icon = new IconOccurrence(
                "energy",
                "Energy",
                "黄色闪电图标。",
                "表示一份能量。",
                "Energy resource",
                IconMeaningStatus.EXPLICIT,
                120,
                240,
                48,
                48);
        var original = new PageFact(
                6,
                "Energy",
                "能量图标带有明确图例。",
                List.of("Energy"),
                List.of(),
                List.of(icon),
                true,
                PageFact.CURRENT_SCHEMA_VERSION);

        var restored = new VisualRulebookPageFactEntity(UUID.randomUUID(), original).toDomain();

        assertThat(restored.iconOccurrences()).containsExactly(icon);
        assertThat(restored.iconInventoryComplete()).isTrue();
    }
}
