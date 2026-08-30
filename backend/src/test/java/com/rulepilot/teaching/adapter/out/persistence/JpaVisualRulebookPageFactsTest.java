package com.rulepilot.teaching.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaVisualRulebookPageFactsTest {

    @Test
    void persistsMinimalFactsAndIgnoresRetiredStoredInventoryColumns() {
        PageFact source = new PageFact(
                8,
                "Systems",
                "Systems can be activated or repaired.",
                List.of(),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(new RuleGroupFact("systems", "Systems", "Activate a system.")));
        VisualRulebookPageFactEntity entity = new VisualRulebookPageFactEntity(UUID.randomUUID(), source);
        entity.sourceDependencies = "legacy data that is no longer interpreted";
        entity.ruleGroupIdentifiers = "legacy data that is no longer interpreted";
        entity.ruleGroupInventoryComplete = true;

        PageFact restored = entity.toDomain();

        assertThat(restored.keywords()).isEmpty();
        assertThat(restored.ruleGroupFacts()).containsExactlyElementsOf(source.ruleGroupFacts());
    }

    @Test
    void retainsEveryDistinctPrintedIdentifierAndSearchTerm() {
        String query = "AA-1 BB-2 CC-3 DD-4 EE-5 FF-6 GG-7 HH-8 II-9 JJ-10 KK-11 LL-12 MM-13 NN-14 OO-15 PP-16";

        assertThat(JpaVisualRulebookPageFacts.printedIdentifiers(query))
                .hasSize(16)
                .contains("aa-1", "ll-12", "mm-13", "nn-14", "oo-15", "pp-16");
        assertThat(JpaVisualRulebookPageFacts.searchTerms(query))
                .contains("mm:*", "13:*", "nn:*", "14:*", "oo:*", "15:*", "pp:*", "16:*");
        assertThat(JpaVisualRulebookPageFacts.printedIdentifiers("ordinary words only")).isEmpty();
    }
}
