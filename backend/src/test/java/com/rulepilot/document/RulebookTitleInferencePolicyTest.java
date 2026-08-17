package com.rulepilot.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulebookTitleInferencePolicyTest {

    @Test
    void keepsANonBlankUploadedTitleEvenWhenItContainsFormerExportMarkers() {
        String uploaded = "  Final Draft English Rules v2  ";

        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        uploaded, "Different Model Title", List.of("Different Model Title")))
                .isEqualTo("Final Draft English Rules v2");
        assertThat(RulebookTitleInferencePolicy.shouldReplaceUploadedTitle(uploaded, "Different Model Title"))
                .isFalse();
    }

    @Test
    void usesTheInferredTitleOnlyWhenTheUploadedTitleIsEmpty() {
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle("  ", "  推断标题  ", List.of()))
                .isEqualTo("推断标题");
        assertThat(RulebookTitleInferencePolicy.shouldReplaceUploadedTitle("  ", "推断标题"))
                .isTrue();
    }
}
