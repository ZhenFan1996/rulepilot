package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualRulebookCatalogPolicyTest {

    @Test
    void refreshingPageEvidenceCannotPromoteStaleTranscriptionsToCurrentSchema() {
        PageFact stale = new PageFact(2, "Old", "Unreadable source was misinterpreted.",
                List.of(), List.of(), PageFact.CURRENT_SCHEMA_VERSION - 1);
        PageFact fresh = new PageFact(2, "Printed rule", "Fresh source observation.", List.of());
        PageFact unaffected = new PageFact(3, "Other page", "Retained observation.", List.of());

        assertThat(VisualRulebookCatalogPolicy.mergeFreshFacts(List.of(stale, unaffected), List.of(fresh)))
                .containsExactly(fresh, unaffected);
        assertThat(VisualRulebookCatalogPolicy.mergeFreshFacts(List.of(stale, unaffected), List.of()))
                .containsExactly(unaffected);
        assertThat(VisualRulebookCatalogPolicy.mergePersistedPageFact(stale, fresh)).isEqualTo(fresh);
    }
}
