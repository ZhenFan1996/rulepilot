package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingOutlineModelTest {

    @Test
    void preservesPageImageEvidenceWithoutLeakingMutableBytes() {
        byte[] source = {1, 2, 3};

        var request = new OutlineRequest(
                 List.of(new PageInput(1, "visual evidence")), List.of(new PageImageInput(1, "image/jpeg", source)));
        source[0] = 9;
        byte[] exposed = request.pageImages().getFirst().content();
        exposed[1] = 9;

        assertThat(request.pageImages().getFirst().content()).containsExactly(1, 2, 3);
    }

    @Test
    void normalizesAndPreservesANaturalLearningGoalForThePlanner() {
        var request = new OutlineRequest(

                List.of(new PageInput(1, "rulebook evidence")),
                List.of(),
                "  先让我能带大家开局，再重点讲行动如何衔接。  ",
                "player");

        assertThat(request.learningGoal()).isEqualTo("先让我能带大家开局，再重点讲行动如何衔接。");
        assertThat(request.learningGoalForPrompt()).isEqualTo(request.learningGoal());
        assertThat(new OutlineRequest( request.pages()).learningGoalForPrompt())
                .isEqualTo("NO_ADDITIONAL_GOAL");
        String detailedGoal = "x".repeat(5_001);
        assertThat(new OutlineRequest(request.pages(), List.of(), detailedGoal, "player").learningGoal())
                .isEqualTo(detailedGoal);
    }

    @Test
    void preservesACompleteSourceInventoryBeyondTheOldSyntheticSlotCeiling() {
        var topic = new TeachingOutlineModel.TopicDraft(
                "opaque",
                "Opaque topic",
                "Teach only the bounded source obligations.",
                true,
                false,
                List.of("R-0"),
                List.of("source_coverage"),
                List.of(1));
        List<SourceCoverageSlotDraft> slots = java.util.stream.IntStream
                .rangeClosed(1, 4_097)
                .mapToObj(index -> new SourceCoverageSlotDraft(
                        "slot-" + index,
                        SourceCoverageRole.SUPPORTING_RULE,
                        "R-" + index,
                        List.of(1),
                        "opaque",
                        SourceCoverageAvailability.SOURCED))
                .toList();

        var outline = new OutlineDraft(
                "Opaque game", "Opaque premise", List.of(topic), slots, true);

        assertThat(outline.sourceCoverageSlots()).hasSameSizeAs(slots);
        assertThat(outline.sourceCoverageSlots().getLast().sourceIdentifier())
                .isEqualTo("R-" + slots.size());
    }

    @Test
    void preservesEveryCatalogedRuleGroupWithoutASecondOutlineLimit() {
        List<String> identifiers = java.util.stream.IntStream.rangeClosed(1, 73)
                .mapToObj(index -> "Source-owned rule group " + index + " " + "detail ".repeat(index))
                .toList();

        var page = new PageInput(41, "  page transcription stays exact  ", List.of(), identifiers, true);

        assertThat(page.text()).isEqualTo("  page transcription stays exact  ");
        assertThat(page.sourceRuleGroupIdentifiers()).containsExactlyElementsOf(identifiers);
    }
}
