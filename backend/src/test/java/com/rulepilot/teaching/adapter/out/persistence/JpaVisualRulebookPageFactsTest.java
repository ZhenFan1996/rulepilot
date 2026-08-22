package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch.RuleFactStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class JpaVisualRulebookPageFactsTest {

    @Test
    void searchesOnlyTheCurrentVisualFactSchema() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(entityManager.createNativeQuery(sql.capture())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        JpaVisualRulebookPageFacts repository = new JpaVisualRulebookPageFacts();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);

        repository.search(UUID.randomUUID(), "MOVE", 2);

        assertThat(sql.getValue())
                .contains(
                        "schema_version = :schemaVersion",
                        "rule_group_identifiers",
                        "rule_group_inventory_complete");
        verify(query).setParameter("schemaVersion", PageFact.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void mapsCompleteRuleGroupInventoriesIntoTypedAnswerEvidenceReadiness() {
        UUID documentVersionId = UUID.randomUUID();
        JpaVisualRulebookPageFacts repository = new JpaVisualRulebookPageFacts();
        EntityManager entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        var currentRuleFact = new PageFact(
                7,
                "Cobalt spindle",
                "Cobalt spindle: The cobalt spindle returns after the final pulse.",
                List.of("cobalt spindle"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("Cobalt spindle"),
                true,
                List.of(new RuleGroupFact(
                        "Cobalt spindle", "Cobalt spindle", "The cobalt spindle returns after the final pulse.")));
        var noRuleContent = new PageFact(
                8,
                "Panel",
                "A descriptive panel has no rule group.",
                List.of("panel"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                true,
                List.of());
        var incomplete = new PageFact(
                9,
                "Pending",
                "The page inventory is not complete.",
                List.of("pending"));
        var jpaQuery = mock(jakarta.persistence.TypedQuery.class);
        when(entityManager.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(VisualRulebookPageFactEntity.class)))
                .thenReturn(jpaQuery);
        when(jpaQuery.setParameter(anyString(), any())).thenReturn(jpaQuery);
        when(jpaQuery.getResultList()).thenReturn(List.of(
                new VisualRulebookPageFactEntity(documentVersionId, currentRuleFact),
                new VisualRulebookPageFactEntity(documentVersionId, noRuleContent),
                new VisualRulebookPageFactEntity(documentVersionId, incomplete)));

        assertThat(repository.findByPageNumbers(documentVersionId, java.util.Set.of(7, 8, 9)))
                .extracting(match -> match.pageNumber() + ":" + match.ruleFactStatus())
                .containsExactly(
                        "7:" + RuleFactStatus.CURRENT_RULE_FACTS,
                        "8:" + RuleFactStatus.NO_RULE_CONTENT,
                        "9:" + RuleFactStatus.FACTS_INCOMPLETE);
    }

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
    void removesRetrievalScaffoldingWithoutDroppingDocumentSpecificTerms() {
        assertThat(JpaVisualRulebookPageFacts.searchTerms(
                        "How does RECOVER work? direct rule clause"))
                .isEqualTo("recover:*");
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

    @Test
    void preserves_external_source_dependencies_as_structured_evidence() {
        var dependency = new SourceDependency("First Session Booklet", List.of("setup"));
        var original = new PageFact(
                3,
                "PLAY A CARD",
                "当前页指向另一份开局资料。",
                List.of("PLAY A CARD"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(dependency));

        var restored = new VisualRulebookPageFactEntity(UUID.randomUUID(), original).toDomain();

        assertThat(restored.sourceDependencies()).containsExactly(dependency);
    }

    @Test
    void preserves_the_complete_page_owned_rule_group_inventory() {
        var original = new PageFact(
                3,
                "MOVE; BUILD",
                "MOVE: 移动有一条完整的可见规则。\nBUILD: 建造有一条完整的可见规则。",
                List.of("MOVE", "BUILD"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of("MOVE", "BUILD"),
                true,
                List.of(
                        new RuleGroupFact("MOVE", "MOVE", "移动有一条完整的可见规则。"),
                        new RuleGroupFact("BUILD", "BUILD", "建造有一条完整的可见规则。")));

        var restored = new VisualRulebookPageFactEntity(UUID.randomUUID(), original).toDomain();

        assertThat(restored.ruleGroupIdentifiers()).containsExactly("MOVE", "BUILD");
        assertThat(restored.ruleGroupFacts()).extracting(RuleGroupFact::identifier)
                .containsExactly("MOVE", "BUILD");
        assertThat(restored.ruleGroupInventoryComplete()).isTrue();
    }

    @Test
    void deserializesAHistoricalCompleteRowSoTheCatalogerCanRebuildIt() {
        UUID documentVersionId = UUID.randomUUID();
        var historical = new PageFact(
                3,
                "MOVE",
                "MOVE: Historical prose ledger.",
                List.of("MOVE"),
                List.of(),
                List.of(),
                false,
                PageFact.CURRENT_SCHEMA_VERSION - 1,
                List.of(),
                List.of("MOVE"),
                true,
                List.of());

        PageFact restored = new VisualRulebookPageFactEntity(documentVersionId, historical).toDomain();

        assertThat(restored.schemaVersion()).isEqualTo(PageFact.CURRENT_SCHEMA_VERSION - 1);
        assertThat(restored.ruleGroupInventoryComplete()).isTrue();
        assertThat(restored.ruleGroupIdentifiers()).containsExactly("MOVE");
        assertThat(restored.ruleGroupFacts()).isEmpty();
    }
}
