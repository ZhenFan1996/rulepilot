package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JpaVisualRulebookPageFactsTest {

    @Test
    void buildsBoundedPrefixOrQueryWithoutConversationalFiller() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "Is clearing three matching wildlife tokens optional and how often can you do this?"))
                .isEqualTo("clearing:* | three:* | matching:* | wildlife:* | token:* | optional:* | often:*");
    }

    @Test
    void extractsChineseFragmentsForImageFactMatching() {
        assertThat(JpaVisualRulebookPageFacts.cjkFragments("野生动物标记可以放在哪些板块上？"))
                .contains("野生", "动物", "标记", "可以", "放在", "板块")
                .doesNotContain("哪些");
    }

    @Test
    void normalizesPlacementWordsForPrintedRulebookTerms() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "Where can I place a Wildlife Token after placing it on Keystone Tiles?"))
                .isEqualTo("place:* | wildlife:* | token:* | after:* | keystone:* | tile:*");
    }
}
