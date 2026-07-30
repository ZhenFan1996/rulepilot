package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import java.util.List;
import org.junit.jupiter.api.Test;

class IconEvidencePolicyTest {

    @Test
    void demotesBracketAndEmojiStandInsThatAreNotExactVisibleText() {
        List<IconOccurrence> sanitized = IconEvidencePolicy.sanitize(List.of(
                icon("Adjacent [house icon] scores 1 point."),
                icon("Each building scores Victory Points 🟢.")));

        assertThat(sanitized).allSatisfy(icon -> {
            assertThat(icon.meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
            assertThat(icon.explanation()).isEmpty();
            assertThat(icon.evidenceText()).isEmpty();
        });
    }

    @Test
    void retainsAPlainLiteralLegendLabel() {
        IconOccurrence sanitized = IconEvidencePolicy.sanitize(List.of(icon("WHEAT"))).getFirst();

        assertThat(sanitized.meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
        assertThat(sanitized.evidenceText()).isEqualTo("WHEAT");
    }

    @Test
    void demotesUsageProseAndNearbyHeadingsThatDoNotNameTheProposedIcon() {
        List<IconOccurrence> sanitized = IconEvidencePolicy.sanitize(List.of(
                icon("carrot", "Flip the cards from the point side to the veggie side."),
                icon("small orange fruit", "POINT SCORING CONDITIONS")));

        assertThat(sanitized).allSatisfy(icon -> {
            assertThat(icon.meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
            assertThat(icon.explanation()).isEmpty();
            assertThat(icon.evidenceText()).isEmpty();
        });
    }

    @Test
    void retainsAVisibleDefinitionThatNamesTheOriginalLanguageIdentity() {
        IconOccurrence sanitized = IconEvidencePolicy.sanitize(List.of(
                        icon("victory point", "The star icon represents one victory point.")))
                .getFirst();

        assertThat(sanitized.meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
    }

    @Test
    void canonicalizesAQuotedPrintedLabelButRejectsAUsageExample() {
        List<IconOccurrence> sanitized = IconEvidencePolicy.sanitize(List.of(
                icon("cabbage", "卡片下方明确标注'CABBAGE'。"),
                icon("cabbage", "Veggies that do not match scoring conditions are not scored (e.g., cabbage).")));

        assertThat(sanitized.getFirst().meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
        assertThat(sanitized.getFirst().evidenceText()).isEqualTo("CABBAGE");
        assertThat(sanitized.get(1).meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
    }

    @Test
    void retainsAnEquivalentPrintedLabelButDemotesShortActionAndConditionText() {
        List<IconOccurrence> sanitized = IconEvidencePolicy.sanitize(List.of(
                icon("rainbow-button-icon", "Rainbow Button"),
                icon("rainbow-button-icon", "Gain a Rainbow Button"),
                icon("button", "Collect no buttons"),
                icon("cat-icon", "Cat Scoring Tiles to be used")));

        assertThat(sanitized.getFirst().meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
        assertThat(sanitized.getFirst().evidenceText()).isEqualTo("Rainbow Button");
        assertThat(sanitized.subList(1, 4)).allSatisfy(icon -> {
            assertThat(icon.meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
            assertThat(icon.explanation()).isEmpty();
            assertThat(icon.evidenceText()).isEmpty();
        });
    }

    @Test
    void requiresPublishedEvidenceToOccurInTheExtractedSourcePage() {
        List<IconOccurrence> sanitized = IconEvidencePolicy.sanitize(
                List.of(
                        icon("victory point", "The star icon represents one victory point."),
                        icon("cat head", "猫头图标表示该行得分条件与猫相关。")),
                "ICON LEGEND\nThe star icon represents one victory point.");

        assertThat(sanitized.getFirst().meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
        assertThat(sanitized.get(1).meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
        assertThat(sanitized.get(1).evidenceText()).isEmpty();
    }

    private static IconOccurrence icon(String evidence) {
        return icon("wheat", evidence);
    }

    private static IconOccurrence icon(String groupKey, String evidence) {
        return new IconOccurrence(
                groupKey,
                "小麦",
                "黄色资源符号。",
                "代表小麦资源。",
                evidence,
                IconMeaningStatus.EXPLICIT,
                100,
                100,
                20,
                20);
    }
}
