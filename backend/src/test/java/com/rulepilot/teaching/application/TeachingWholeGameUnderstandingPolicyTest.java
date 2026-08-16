package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.teaching.TeachingOutlineModel.GlobalConceptDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDependencyDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingWholeGameUnderstandingPolicyTest {

    @Test
    void validatesAndPersistsAnAgentChosenCrossChapterMentalModel() {
        OutlineDraft outline = completeOutline();

        TeachingSourceCoverageContract.requireCompleteModelContract(request(), outline);
        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", outline);

        assertThat(plan.wholeGameContext().evidenceBound()).isTrue();
        assertThat(plan.wholeGameContext().concepts())
                .extracting(concept -> concept.conceptId() + ":" + concept.relatedTopicKeys())
                .containsExactly("shared-state:[observe-state]", "conditional-change:[apply-change]");
        assertThat(plan.wholeGameContext().topicDependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.prerequisiteTopicKey()).isEqualTo("observe-state");
            assertThat(dependency.dependentTopicKey()).isEqualTo("apply-change");
        });
        assertThat(plan.sections()).allSatisfy(section -> assertThat(section.coverageTags())
                .contains(TeachingWholeGameUnderstandingPolicy.CONTRACT_TAG));
    }

    @Test
    void rejectsAGlobalConceptThatUsesAnIdentifierOutsideItsRelatedSourceOwners() {
        OutlineDraft valid = completeOutline();
        var invalidConcept = new GlobalConceptDraft(
                "misbound-change",
                "错误绑定",
                "把另一个章节的来源错误绑定到本章。",
                List.of("R-beta"),
                List.of(2),
                List.of("observe-state"),
                List.of());
        OutlineDraft invalid = new OutlineDraft(
                valid.gameTitle(),
                valid.premise(),
                valid.topics(),
                valid.sourceCoverageSlots(),
                true,
                new com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft(
                        valid.wholeGameUnderstanding().summary(),
                        List.of(invalidConcept),
                        List.of()));

        assertThatThrownBy(() -> TeachingSourceCoverageContract.requireCompleteModelContract(request(), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no matching sourced slot");
    }

    @Test
    void allowsANarrowSourceOwnedChapterOutsideTheGlobalConceptGraph() {
        OutlineDraft valid = completeOutline();
        OutlineDraft incomplete = new OutlineDraft(
                valid.gameTitle(),
                valid.premise(),
                valid.topics(),
                valid.sourceCoverageSlots(),
                true,
                new com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft(
                        "只认识第一项关系。",
                        List.of(valid.wholeGameUnderstanding().concepts().getFirst()),
                        List.of()));

        TeachingSourceCoverageContract.requireCompleteModelContract(request(), incomplete);
        var plan = new TeachingPlanFactory().create(UUID.randomUUID(), "player", incomplete);

        assertThat(plan.sections()).extracting(section -> section.topicKey())
                .containsExactly("observe-state", "apply-change");
        assertThat(plan.wholeGameContext().concepts())
                .extracting(concept -> concept.conceptId())
                .containsExactly("shared-state");
    }

    private OutlineRequest request() {
        return new OutlineRequest(List.of(
                new PageInput(1, "R-alpha establishes the observable shared state."),
                new PageInput(2, "R-beta conditionally changes that shared state.")));
    }

    private OutlineDraft completeOutline() {
        List<TopicDraft> topics = List.of(
                topic("observe-state", "R-alpha", 1),
                topic("apply-change", "R-beta", 2));
        List<SourceCoverageSlotDraft> slots = List.of(
                slot("alpha-source", "R-alpha", 1, "observe-state", "observe-state-unit"),
                slot("beta-source", "R-beta", 2, "apply-change", "apply-change-unit"));
        var understanding = new com.rulepilot.teaching.TeachingOutlineModel.WholeGameUnderstandingDraft(
                "先认识共享状态，再理解条件如何改变它。",
                List.of(
                        new GlobalConceptDraft(
                                "shared-state",
                                "共享状态",
                                "先识别所有人共同观察的状态。",
                                List.of("R-alpha"),
                                List.of(1),
                                List.of("observe-state"),
                                List.of()),
                        new GlobalConceptDraft(
                                "conditional-change",
                                "条件变化",
                                "在已认识状态后判断何时改变它。",
                                List.of("R-beta"),
                                List.of(2),
                                List.of("apply-change"),
                                List.of("shared-state"))),
                List.of(new TopicDependencyDraft(
                        "observe-state",
                        "apply-change",
                        "先识别状态，才能判断后续变化。")));
        return new OutlineDraft("Opaque system", "学习两项相互依赖的规则关系。", topics, slots, true, understanding);
    }

    private TopicDraft topic(String key, String identifier, int page) {
        return new TopicDraft(
                key,
                "主题 " + key,
                "能够使用 " + identifier + " 对应的来源关系。",
                true,
                false,
                List.of(identifier),
                List.of("source_coverage"),
                List.of(page));
    }

    private SourceCoverageSlotDraft slot(
            String id, String identifier, int page, String owner, String unit) {
        return new SourceCoverageSlotDraft(
                id,
                SourceCoverageRole.SUPPORTING_RULE,
                identifier,
                List.of(page),
                owner,
                unit,
                SourceCoverageAvailability.SOURCED);
    }
}
