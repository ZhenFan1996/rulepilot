package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingConceptSourceOwnershipTest {

    @Test
    void assignsOneExactUnownedConceptSourceWithoutRegeneratingTheValidatedOutline() {
        OutlineRequest request = request();
        OutlineDraft original = outline(List.of(concept("change", "R-gamma", "apply-change")));

        OutlineDraft repaired = TeachingConceptSourceOwnership.repairMissingOwners(request, original);

        assertThat(repaired).isNotSameAs(original);
        assertThat(repaired.gameTitle()).isEqualTo(original.gameTitle());
        assertThat(repaired.premise()).isEqualTo(original.premise());
        assertThat(repaired.wholeGameUnderstanding()).isSameAs(original.wholeGameUnderstanding());
        assertThat(repaired.sourceCoverageSlots()).hasSize(original.sourceCoverageSlots().size() + 1);
        assertThat(repaired.sourceCoverageSlots().getLast()).satisfies(slot -> {
            assertThat(slot.role()).isEqualTo(SourceCoverageRole.SUPPORTING_RULE);
            assertThat(slot.sourceIdentifier()).isEqualTo("R-gamma");
            assertThat(slot.sourcePageNumbers()).containsExactly(2);
            assertThat(slot.ownerTopicKey()).isEqualTo("apply-change");
        });
        assertThat(repaired.topics()).zipSatisfy(original.topics(), (actual, previous) -> {
            assertThat(actual.key()).isEqualTo(previous.key());
            assertThat(actual.title()).isEqualTo(previous.title());
            assertThat(actual.objective()).isEqualTo(previous.objective());
        });
        assertThatNoException().isThrownBy(() ->
                TeachingSourceCoverageContract.requireCompleteModelContract(request, repaired));
    }

    @Test
    void groupsARepeatedExactConceptSourceUnderOneCommonChapterOwner() {
        OutlineDraft original = outline(List.of(
                concept("change-a", "R-gamma", "apply-change"),
                concept("change-b", "R-gamma", "apply-change")));

        OutlineDraft repaired = TeachingConceptSourceOwnership.repairMissingOwners(request(), original);

        assertThat(repaired.sourceCoverageSlots())
                .filteredOn(slot -> slot.sourceIdentifier().equals("R-gamma"))
                .singleElement()
                .satisfies(slot -> assertThat(slot.ownerTopicKey()).isEqualTo("apply-change"));
        assertThatNoException().isThrownBy(() ->
                TeachingSourceCoverageContract.requireCompleteModelContract(request(), repaired));
    }

    @Test
    void refusesToFabricateAConceptSourceThatIsAbsentFromItsBoundPages() {
        OutlineRequest missingEvidence = new OutlineRequest(List.of(
                page(1, "R-alpha establishes the visible state.", "R-alpha"),
                page(2, "R-beta changes that state.", "R-beta")));
        OutlineDraft original = outline(List.of(concept("change", "R-gamma", "apply-change")));

        OutlineDraft repaired = TeachingConceptSourceOwnership.repairMissingOwners(missingEvidence, original);

        assertThat(repaired).isSameAs(original);
        assertThat(repaired.sourceCoverageSlots())
                .noneMatch(slot -> slot.sourceIdentifier().equals("R-gamma"));
    }

    @Test
    void neverDuplicatesAnExactSourceThatAlreadyHasAChapterOwner() {
        OutlineDraft original = outline(List.of(concept("change", "R-alpha", "apply-change")));

        OutlineDraft repaired = TeachingConceptSourceOwnership.repairMissingOwners(request(), original);

        assertThat(repaired).isSameAs(original);
        assertThat(repaired.sourceCoverageSlots())
                .filteredOn(slot -> slot.sourceIdentifier().equals("R-alpha"))
                .singleElement();
    }

    private OutlineRequest request() {
        return new OutlineRequest(List.of(
                page(1, "R-alpha establishes the visible state.", "R-alpha"),
                page(2, "R-beta changes that state. R-gamma limits that change.", "R-beta", "R-gamma")));
    }

    private PageInput page(int pageNumber, String text, String... identifiers) {
        return new PageInput(pageNumber, text, List.of(), List.of(identifiers), true);
    }

    private OutlineDraft outline(List<GlobalConceptDraft> additionalConcepts) {
        List<TopicDraft> topics = List.of(
                topic("observe-state", "R-alpha", 1),
                topic("apply-change", "R-beta", 2));
        List<SourceCoverageSlotDraft> slots = List.of(
                slot("alpha-source", "R-alpha", 1, "observe-state"),
                slot("beta-source", "R-beta", 2, "apply-change"));
        List<GlobalConceptDraft> concepts = new java.util.ArrayList<>();
        concepts.add(concept("state", "R-alpha", "observe-state"));
        concepts.addAll(additionalConcepts);
        return new OutlineDraft(
                "Opaque system",
                "Understand a visible state and its permitted changes.",
                topics,
                slots,
                true,
                new WholeGameUnderstandingDraft(
                        "Observe the shared state before applying a change.",
                        concepts,
                        List.of()));
    }

    private GlobalConceptDraft concept(String id, String identifier, String topicKey) {
        int page = topicKey.equals("observe-state") ? 1 : 2;
        return new GlobalConceptDraft(
                id,
                "Concept " + id,
                "Explain the evidence-bound relation for " + id + ".",
                List.of(identifier),
                List.of(page),
                List.of(topicKey),
                List.of());
    }

    private TopicDraft topic(String key, String identifier, int page) {
        return new TopicDraft(
                key,
                "Topic " + key,
                "Use the source relation " + identifier + ".",
                true,
                false,
                List.of(identifier),
                List.of("source_coverage"),
                List.of(page));
    }

    private SourceCoverageSlotDraft slot(String id, String identifier, int page, String owner) {
        return new SourceCoverageSlotDraft(
                id,
                SourceCoverageRole.SUPPORTING_RULE,
                identifier,
                List.of(page),
                owner,
                id,
                SourceCoverageAvailability.SOURCED);
    }
}
