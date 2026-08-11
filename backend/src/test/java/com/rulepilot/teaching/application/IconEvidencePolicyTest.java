package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.util.List;
import org.junit.jupiter.api.Test;

class IconEvidencePolicyTest {

    @Test
    void preservesStructuredModelStatusesWithoutReinterpretingTheirProse() {
        List<IconOccurrence> icons = List.of(
                icon(IconMeaningStatus.EXPLICIT, "opaque evidence A", ""),
                icon(IconMeaningStatus.IDENTIFIED, "opaque label B", "opaque label B"),
                icon(IconMeaningStatus.UNEXPLAINED, "", ""));

        assertThat(IconEvidencePolicy.sanitize(icons)).containsExactlyElementsOf(icons);
        assertThat(IconEvidencePolicy.sanitize(null)).isEmpty();
    }

    @Test
    void keepsExplicitMeaningOnlyWhenItsEvidenceOccursOnTheSourcePage() {
        IconOccurrence supported = icon(
                IconMeaningStatus.EXPLICIT,
                "A star grants one point.",
                "");
        IconOccurrence unsupported = icon(
                IconMeaningStatus.EXPLICIT,
                "A circle grants two points.",
                "");

        List<IconOccurrence> result = IconEvidencePolicy.sanitize(
                List.of(supported, unsupported),
                "ICON LEDGER\nA star grants one point.\nNo other definition is printed.");

        assertThat(result.getFirst()).isEqualTo(supported);
        assertDemoted(result.get(1));
    }

    @Test
    void sourceComparisonNormalizesUnicodeCaseAndWhitespaceOnly() {
        IconOccurrence icon = icon(
                IconMeaningStatus.EXPLICIT,
                "ＦＯＯ   BAR",
                "");

        assertThat(IconEvidencePolicy.sanitize(List.of(icon), "prefix foo bar suffix").getFirst())
                .isEqualTo(icon);
    }

    @Test
    void independentlyVerifiedLiteralLabelCanRetainAnIdentifiedCrop() {
        IconOccurrence verified = icon(
                IconMeaningStatus.IDENTIFIED,
                "ALPHA-7",
                "ALPHA-7");
        IconOccurrence conflicting = icon(
                IconMeaningStatus.IDENTIFIED,
                "ALPHA-7",
                "BETA-9");

        List<IconOccurrence> result = IconEvidencePolicy.sanitize(
                List.of(verified, conflicting),
                "The text layer does not contain either crop label.");

        assertThat(result.getFirst()).isEqualTo(verified);
        assertDemoted(result.get(1));
    }

    @Test
    void unexplainedObservationNeverGainsMeaningFromNearbySourceText() {
        IconOccurrence unexplained = icon(IconMeaningStatus.UNEXPLAINED, "", "ALPHA-7");

        assertThat(IconEvidencePolicy.sanitize(
                        List.of(unexplained),
                        "ALPHA-7 means three points.").getFirst())
                .isEqualTo(unexplained);
    }

    @Test
    void identityCompatibilityIsExactAfterMechanicalNormalization() {
        assertThat(IconEvidencePolicy.compatibleIdentity(" Ａlpha   7 ", "alpha 7")).isTrue();
        assertThat(IconEvidencePolicy.compatibleIdentity("WOOD", "resource cube - wood")).isFalse();
        assertThat(IconEvidencePolicy.compatibleIdentity("", "anything")).isFalse();
    }

    private static IconOccurrence icon(
            IconMeaningStatus status, String evidenceText, String verifiedVisualLabel) {
        return new IconOccurrence(
                "opaque-group",
                "不透明符号",
                "一个边界明确的可见图形。",
                status == IconMeaningStatus.EXPLICIT ? "模型给出的结构化含义。" : "",
                evidenceText,
                verifiedVisualLabel,
                status,
                100,
                100,
                24,
                24);
    }

    private static void assertDemoted(IconOccurrence icon) {
        assertThat(icon.meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
        assertThat(icon.explanation()).isEmpty();
        assertThat(icon.evidenceText()).isEmpty();
    }
}
