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

    @Test
    void groupsAliasesOnlyWhenTheirIndependentVisualLabelsAgreeWithTheirSemanticKeys() {
        IconOccurrence explained = iconWithVerifiedLabel(
                "carrot",
                "胡萝卜图标",
                "整张卡牌中央有橙色胡萝卜插图。",
                "代表胡萝卜类别。",
                "CARROT",
                "CARROT",
                IconMeaningStatus.EXPLICIT,
                100,
                88,
                170);
        IconOccurrence repeated = iconWithVerifiedLabel(
                "carrot icon",
                "胡萝卜图标",
                "橙色胡萝卜剪影。",
                "",
                "",
                "CARROT",
                IconMeaningStatus.UNEXPLAINED,
                300,
                60,
                60);
        IconOccurrence mismatched = iconWithVerifiedLabel(
                "point",
                "点数图标",
                "绿色三角形。",
                "",
                "",
                "SALAD",
                IconMeaningStatus.UNEXPLAINED,
                500,
                60,
                60);

        var projection = RulebookIconGlossaryPolicy.project(
                UUID.randomUUID(),
                List.of(page(2, explained), page(7, repeated, mismatched)));

        assertThat(projection.groups()).hasSize(2);
        assertThat(projection.groups().stream()
                        .filter(group -> group.meaningStatus() == IconMeaningStatus.EXPLICIT)
                        .findFirst())
                .hasValueSatisfying(group -> {
                    assertThat(group.occurrences()).singleElement()
                            .extracting(RulebookIconGlossaryPolicy.OccurrenceView::pageNumber)
                            .isEqualTo(7);
                    assertThat(group.evidenceText()).isEqualTo("CARROT");
                });
        assertThat(projection.groups().stream()
                        .filter(group -> group.meaningStatus() == IconMeaningStatus.UNEXPLAINED)
                        .findFirst())
                .hasValueSatisfying(group -> assertThat(group.name()).isEqualTo("点数图标"));
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

    private static IconOccurrence iconWithVerifiedLabel(
            String groupKey,
            String name,
            String visible,
            String explanation,
            String evidence,
            String verifiedLabel,
            IconMeaningStatus status,
            int x,
            int width,
            int height) {
        return new IconOccurrence(
                groupKey, name, visible, explanation, evidence, verifiedLabel, status, x, 200, width, height);
    }
}
