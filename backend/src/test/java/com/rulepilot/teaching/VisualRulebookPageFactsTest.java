package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookPageFactsTest {

    @Test
    void keepsReadablePageEvidenceWithoutACompletenessOrKeywordGate() {
        PageFact readable = new PageFact(
                4,
                "Systems and repair",
                "Damaged systems may be repaired.",
                List.of(),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of(new RuleGroupFact("repair", "Repair", "Repair one damaged system.")));

        assertThat(readable.keywords()).isEmpty();
        assertThat(readable.ruleGroupFacts()).extracting(RuleGroupFact::identifier).containsExactly("repair");
        assertThatNoException().isThrownBy(() -> new PageFact(
                5,
                "Illustration",
                "No reliably readable gameplay rule appears here.",
                List.of(),
                List.of(),
                PageFact.CURRENT_SCHEMA_VERSION,
                List.of()));
    }
}
