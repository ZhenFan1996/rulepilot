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
        assertThat(RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle(
                        uploaded, "Different Model Title"))
                .isFalse();
    }

    @Test
    void usesTheInferredTitleOnlyWhenTheUploadedTitleIsEmpty() {
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle("  ", "  推断标题  ", List.of()))
                .isEqualTo("推断标题");
        assertThat(RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle("  ", "推断标题"))
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
        assertThat(RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle(
                        "Harbor Nova rulebook EN v4 12pages", "Harbor Nova"))
                .isTrue();
        assertThat(RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle(
                        "星港 中文规则书 v2", "星港"))
                .isTrue();
    }

    @Test
    void triangulatesAStandaloneSourceTitleWhenTheModelReturnsAHeadingOrRepeatsTheNoisyLabel() {
        List<String> opening = List.of("""
                Lantern Relay - Original RulePilot Demonstration Rules
                License: CC0 1.0
                LANTERN RELAY
                OBJECTIVE AND HOW TO WIN
                """);

        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Lantern Relay rulebook EN v4 12pages",
                        "Lantern Relay - Original RulePilot Demonstration Rules",
                        opening))
                .isEqualTo("Lantern Relay");
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Lantern Relay rulebook EN v4 12pages",
                        "Lantern Relay rulebook EN v4 12pages",
                        opening))
                .isEqualTo("Lantern Relay");
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
        assertThat(RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle(
                        "Cart release notes", "Art"))
                .isFalse();
        assertThat(RulebookTitleInferencePolicy.selectPlayerTitle(
                        "Cart release notes",
                        "Cart art catalog",
                        List.of("CART\nCart art catalog")))
                .as("one shared Latin word is too weak to replace a player-authored identity")
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
