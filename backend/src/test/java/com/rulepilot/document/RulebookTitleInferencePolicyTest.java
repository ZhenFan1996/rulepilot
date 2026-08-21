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

    @Test
    void usesASourceConfirmedTitleContainedInANoisyUploadLabel() {
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Harbor Nova rulebook EN v4 12pages",
                        "Harbor Nova",
                        List.of("Harbor Nova - Original Demonstration Rules", "SETUP")))
                .isEqualTo("Harbor Nova");
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "星港 中文规则书 v2",
                        "星港",
                        List.of("星港 游戏规则", "准备")))
                .isEqualTo("星港");
    }

    @Test
    void doesNotTrustAnUnrelatedOrEmbeddedTitle() {
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "A home-made prototype",
                        "Different Model Title",
                        List.of("Different Model Title")))
                .isEqualTo("A home-made prototype");
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Cart release notes",
                        "Art",
                        List.of("Art")))
                .isEqualTo("Cart release notes");
    }

    @Test
    void requiresTheContainedIdentityToAppearNearTheStartOfTheRulebook() {
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Harbor Nova rulebook EN v4 12pages",
                        "Harbor Nova",
                        List.of("Anonymous quick-start guide", "SETUP", "Harbor Nova is mentioned in an appendix")))
                .isEqualTo("Harbor Nova rulebook EN v4 12pages");
    }
}
