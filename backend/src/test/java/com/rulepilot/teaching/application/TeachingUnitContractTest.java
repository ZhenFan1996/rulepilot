package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.util.List;
import java.util.stream.IntStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingUnitContractTest {

    @Test
    void keepsTheOutlineAgentsExplicitGroupingInTheImmutablePlan() {
        TopicDraft topic = topic("coherent-topic", List.of("K-one", "K-two", "K-three"), List.of(4));
        OutlineDraft outline = new OutlineDraft(
                "Opaque lesson",
                "The source defines its own concepts.",
                List.of(topic),
                List.of(
                        slot("slot-one", "K-one", "combined-choice", "coherent-topic", 4),
                        slot("slot-two", "K-two", "combined-choice", "coherent-topic", 4),
                        slot("slot-three", "K-three", "separate-condition", "coherent-topic", 4)),
                true);

        TeachingSourceCoverageContract.validateAgainstSources(
                new OutlineRequest(List.of(new PageInput(4, "K-one and K-two form one relation. K-three is separate."))),
                outline);
        var planned = new TeachingPlanFactory()
                .create(UUID.randomUUID(), "player", outline)
                .sections()
                .getFirst();

        assertThat(TeachingUnitContract.decodeUnits(planned.retrievalQueries()))
                .extracting(TeachingUnitContract.Unit::unitId)
                .containsExactly("combined-choice", "separate-condition");
        assertThat(TeachingUnitContract.decodeUnits(planned.retrievalQueries()).getFirst().sourceIdentifiers())
                .containsExactly("K-one", "K-two");
        assertThat(TeachingUnitContract.decodeUnits(planned.retrievalQueries()).getFirst().sourcePages("K-one"))
                .containsExactly(4);
        assertThat(TeachingUnitContract.decodeUnits(planned.retrievalQueries()).getFirst().sourcePages("K-two"))
                .containsExactly(4);
        assertThat(TeachingUnitContract.decodeUnits(planned.retrievalQueries()).getFirst())
                .satisfies(unit -> {
                    assertThat(unit.typed()).isTrue();
                    assertThat(unit.roles()).containsExactly(SourceCoverageRole.SUPPORTING_RULE);
                    assertThat(unit.availability()).isEqualTo(SourceCoverageAvailability.SOURCED);
                });
    }

    @Test
    void stillReadsPersistedVersionOneUnitContractsWithoutInventingPageOwnership() {
        String legacy = "teaching-unit-v1.dW5pdA.Ul9vbGQ";

        var unit = TeachingUnitContract.decodeUnits(List.of(legacy)).getFirst();

        assertThat(unit.unitId()).isEqualTo("unit");
        assertThat(unit.sourceIdentifiers()).containsExactly("R_old");
        assertThat(unit.sourcePages()).isEmpty();
        assertThat(unit.typed()).isFalse();
        assertThat(unit.roles()).isEmpty();
        assertThat(unit.availability()).isNull();
    }

    @Test
    void keepsPersistedVersionTwoPageOwnershipExplicitlyLegacy() {
        String versionTwo = TeachingUnitContract.encode(
                new TeachingUnitContract.Unit("unit", java.util.Map.of("R_old", List.of(3))));

        var unit = TeachingUnitContract.decodeUnits(List.of(versionTwo)).getFirst();

        assertThat(unit.sourcePages()).containsExactly(3);
        assertThat(unit.typed()).isFalse();
        assertThat(unit.availability()).isNull();
    }

    @Test
    void versionThreeRoundTripsRoleAndAvailabilityForIndependentMixedChapterUnits() {
        List<SourceCoverageSlotDraft> slots = List.of(
                new SourceCoverageSlotDraft(
                        "sourced-slot",
                        SourceCoverageRole.LEGAL_ACTION,
                        "R-action",
                        List.of(2),
                        "mixed-topic",
                        "sourced-unit",
                        SourceCoverageAvailability.SOURCED),
                new SourceCoverageSlotDraft(
                        "missing-slot",
                        SourceCoverageRole.ENDING,
                        "External ending procedure",
                        List.of(4),
                        "mixed-topic",
                        "missing-unit",
                        SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE),
                new SourceCoverageSlotDraft(
                        "unresolved-slot",
                        SourceCoverageRole.SCORING,
                        "Unresolved scoring relation",
                        List.of(),
                        "mixed-topic",
                        "unresolved-unit",
                        SourceCoverageAvailability.UNRESOLVED));

        List<String> encoded = TeachingUnitContract.encodeUnits(slots);
        List<TeachingUnitContract.Unit> decoded = TeachingUnitContract.decodeUnits(encoded);

        assertThat(encoded).allMatch(value -> value.startsWith("teaching-unit-v3."));
        assertThat(decoded).extracting(TeachingUnitContract.Unit::availability)
                .containsExactly(
                        SourceCoverageAvailability.SOURCED,
                        SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE,
                        SourceCoverageAvailability.UNRESOLVED);
        assertThat(decoded).extracting(TeachingUnitContract.Unit::roles)
                .containsExactly(
                        List.of(SourceCoverageRole.LEGAL_ACTION),
                        List.of(SourceCoverageRole.ENDING),
                        List.of(SourceCoverageRole.SCORING));
        assertThat(TeachingUnitContract.retrievalIdentifiers(encoded))
                .containsExactly("R-action", "External ending procedure")
                .doesNotContain("Unresolved scoring relation");
    }

    @Test
    void rejectsMixedAvailabilityInsideOneTeachingUnit() {
        List<SourceCoverageSlotDraft> slots = List.of(
                new SourceCoverageSlotDraft(
                        "sourced-slot",
                        SourceCoverageRole.CORE_LOOP,
                        "R-loop",
                        List.of(2),
                        "mixed-topic",
                        "blurred-unit",
                        SourceCoverageAvailability.SOURCED),
                new SourceCoverageSlotDraft(
                        "missing-slot",
                        SourceCoverageRole.CORE_LOOP,
                        "External loop procedure",
                        List.of(3),
                        "mixed-topic",
                        "blurred-unit",
                        SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE));

        assertThatThrownBy(() -> TeachingUnitContract.encodeUnits(slots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot mix source availability");
    }

    @Test
    void preservesEverySourceWhenTheOutlineAgentGroupsALargeCoherentUnit() {
        List<SourceCoverageSlotDraft> slots = IntStream.rangeClosed(1, 19)
                .mapToObj(index -> slot(
                        "slot-" + index,
                        "Source relation " + index,
                        "complete-reward-table",
                        "reward-resolution",
                        8))
                .toList();

        List<String> encoded = TeachingUnitContract.encodeUnits(slots);

        assertThat(encoded).hasSize(1);
        assertThat(TeachingUnitContract.decodeUnits(encoded).getFirst().sourceIdentifiers())
                .containsExactlyElementsOf(slots.stream()
                        .map(SourceCoverageSlotDraft::sourceIdentifier)
                        .toList());
    }

    @Test
    void rejectsAUnitThatWouldBlurTwoChapterOwners() {
        OutlineDraft outline = new OutlineDraft(
                "Opaque lesson",
                "The source defines its own concepts.",
                List.of(
                        topic("topic-a", List.of("K-one"), List.of(1)),
                        topic("topic-b", List.of("K-two"), List.of(2))),
                List.of(
                        slot("slot-one", "K-one", "shared-unit", "topic-a", 1),
                        slot("slot-two", "K-two", "shared-unit", "topic-b", 2)),
                true);

        assertThatThrownBy(() -> TeachingSourceCoverageContract.validateAgainstSources(
                        new OutlineRequest(List.of(
                                new PageInput(1, "K-one is one sourced relation."),
                                new PageInput(2, "K-two is another sourced relation."))),
                        outline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot span multiple chapter owners");
    }

    private TopicDraft topic(String key, List<String> queries, List<Integer> pages) {
        return new TopicDraft(
                key,
                "自主演绎 " + key,
                "根据当前来源决定本章应当教会的关系。",
                true,
                false,
                queries,
                List.of("source_coverage"),
                pages);
    }

    private SourceCoverageSlotDraft slot(
            String slotId,
            String identifier,
            String teachingUnitId,
            String owner,
            int page) {
        return new SourceCoverageSlotDraft(
                slotId,
                SourceCoverageRole.SUPPORTING_RULE,
                identifier,
                List.of(page),
                owner,
                teachingUnitId,
                SourceCoverageAvailability.SOURCED);
    }
}
