package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeachingLessonModelTest {

    @Test
    void sectionRequestRetainsCompleteContinuityVisualAndRuleInputsPastFormerContentCaps() {
        List<PriorSectionContext> priorSections = IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new PriorSectionContext(
                        "topic-" + index, "Chapter " + index, "Closing step " + index))
                .toList();
        List<PageImageInput> pageImages = IntStream.rangeClosed(1, 13)
                .mapToObj(page -> new PageImageInput(page, "image/png", new byte[] {(byte) page}, 1, 1))
                .toList();
        String completeRuleIntent = "完整规则意图".repeat(80);

        SectionRequest request = new SectionRequest(
                "topic-current",
                "Current chapter",
                "Explain every grounded rule",
                List.of("core_loop"),
                priorSections,
                List.of(new EvidenceInput(
                        UUID.randomUUID(), "RULES", "Complete evidence", "Visible source fact", 1, 1)),
                pageImages,
                List.of(completeRuleIntent),
                List.of(),
                "owner",
                "complete chapter scope");

        assertThat(request.priorSections()).hasSize(3);
        assertThat(request.pageImages()).hasSize(13);
        assertThat(request.requiredRuleIntents()).containsExactly(completeRuleIntent);
    }
}
