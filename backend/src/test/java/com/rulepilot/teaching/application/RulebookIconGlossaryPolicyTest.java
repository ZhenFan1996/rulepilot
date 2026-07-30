package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RulebookIconGlossaryPolicyTest {

    @Test
    void combines_an_unexplained_repeat_with_the_same_explicitly_defined_icon() {
        UUID documentVersionId = UUID.randomUUID();
        IconOccurrence explained = icon(
                "victory point",
                "Victory point",
                "红色五角星。",
                "表示胜利点。",
                "Victory point",
                IconMeaningStatus.EXPLICIT,
                100);
        IconOccurrence repeated = icon(
                "victory-point",
                "胜利点图标",
                "红色五角星。",
                "",
                "",
                IconMeaningStatus.UNEXPLAINED,
                400);

        var projection = RulebookIconGlossaryPolicy.project(
                documentVersionId,
                List.of(page(2, explained), page(7, repeated)));

        assertThat(projection.groups()).singleElement().satisfies(group -> {
            assertThat(group.explanation()).isEqualTo("表示胜利点。");
            assertThat(group.meaningStatus()).isEqualTo(IconMeaningStatus.EXPLICIT);
            assertThat(group.occurrences()).extracting(
                            RulebookIconGlossaryPolicy.OccurrenceView::pageNumber)
                    .containsExactly(2, 7);
        });
    }

    @Test
    void keeps_conflicting_meanings_separate_instead_of_merging_by_a_tempting_label() {
        UUID documentVersionId = UUID.randomUUID();
        IconOccurrence cost = icon(
                "star",
                "Star",
                "白色星形。",
                "支付这个图标所示费用。",
                "Pay this cost",
                IconMeaningStatus.EXPLICIT,
                100);
        IconOccurrence score = icon(
                "star",
                "Star",
                "白色星形。",
                "结算时获得一分。",
                "Gain 1 point",
                IconMeaningStatus.EXPLICIT,
                300);

        var projection = RulebookIconGlossaryPolicy.project(
                documentVersionId,
                List.of(page(3, cost), page(9, score)));

        assertThat(projection.groups()).hasSize(2);
        assertThat(projection.conflictingGroupKeys()).containsExactly("star");
        assertThat(projection.groups()).extracting(RulebookIconGlossaryPolicy.IconGroup::explanation)
                .containsExactlyInAnyOrder("支付这个图标所示费用。", "结算时获得一分。");
    }

    @Test
    void never_promotes_an_unexplained_symbol_into_a_rule_meaning() {
        var projection = RulebookIconGlossaryPolicy.project(
                UUID.randomUUID(),
                List.of(page(
                        5,
                        icon(
                                "green circle leaf",
                                "绿色叶片圆标",
                                "绿色圆形内有叶片。",
                                "",
                                "",
                                IconMeaningStatus.UNEXPLAINED,
                                120))));

        assertThat(projection.groups()).singleElement().satisfies(group -> {
            assertThat(group.meaningStatus()).isEqualTo(IconMeaningStatus.UNEXPLAINED);
            assertThat(group.explanation()).isNull();
            assertThat(group.evidenceText()).isNull();
        });
    }

    private static PageFact page(int pageNumber, IconOccurrence... icons) {
        return new PageFact(
                pageNumber,
                "Page " + pageNumber,
                "Visible page facts.",
                List.of("page"),
                List.of(),
                List.of(icons),
                true,
                PageFact.CURRENT_SCHEMA_VERSION);
    }

    private static IconOccurrence icon(
            String groupKey,
            String name,
            String visible,
            String explanation,
            String evidence,
            IconMeaningStatus status,
            int x) {
        return new IconOccurrence(
                groupKey, name, visible, explanation, evidence, status, x, 200, 60, 60);
    }
}
