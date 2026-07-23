package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingVisualEvidenceSelectorTest {

    @Test
    void selectsTheMostTopicRelevantEvidencePagesBeforeEarlierGenericPages() {
        PlannedSection planned = new PlannedSection(
                1,
                "orbiter-setup",
                "Orbiter supply",
                "Explain the orbiter supply.",
                true,
                true,
                List.of("orbiter supply"),
                List.of("components"));
        UUID versionId = UUID.randomUUID();
        RuleEvidence generic = evidence(versionId, "Components", 5, new RulePageImage(5, "image/jpeg", new byte[] {5}, 800, 1200));
        RuleEvidence bestMatch = evidence(versionId, "Orbiter supply", 2, new RulePageImage(2, "image/jpeg", new byte[] {2}, 800, 1200));
        RuleEvidence secondMatch = evidence(versionId, "Orbiter storage", 3, new RulePageImage(3, "image/jpeg", new byte[] {3}, 800, 1200));

        List<PageImageInput> selected = TeachingVisualEvidenceSelector.select(
                planned, List.of(generic, bestMatch, secondMatch), true);

        assertThat(selected).extracting(PageImageInput::pageNumber).containsExactly(2, 3);
    }

    @Test
    void keepsImageOnlyPagesEligibleButNeverAttachesImagesWithoutModelSupport() {
        PlannedSection planned = new PlannedSection(
                1,
                "setup",
                "Setup",
                "Explain setup.",
                true,
                false,
                List.of("setup"),
                List.of("setup"));
        RuleEvidence imageOnly = new RuleEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SETUP",
                "Visual setup page",
                TeachingVisualEvidenceSelector.VISUAL_PAGE_PLACEHOLDER,
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {4}, 800, 1200)));

        assertThat(TeachingVisualEvidenceSelector.select(planned, List.of(imageOnly), true))
                .extracting(PageImageInput::pageNumber)
                .containsExactly(4);
        assertThat(TeachingVisualEvidenceSelector.select(planned, List.of(imageOnly), false)).isEmpty();
    }

    private RuleEvidence evidence(UUID versionId, String heading, int page, RulePageImage image) {
        return new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "COMPONENTS",
                heading,
                heading,
                page,
                page,
                List.of(image));
    }
}
