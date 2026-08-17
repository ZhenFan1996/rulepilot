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
    }

    @Test
    void stillReadsPersistedVersionOneUnitContractsWithoutInventingPageOwnership() {
        String legacy = "teaching-unit-v1.dW5pdA.Ul9vbGQ";

        var unit = TeachingUnitContract.decodeUnits(List.of(legacy)).getFirst();

        assertThat(unit.unitId()).isEqualTo("unit");
        assertThat(unit.sourceIdentifiers()).containsExactly("R_old");
        assertThat(unit.sourcePages()).isEmpty();
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
