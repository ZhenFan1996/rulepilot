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
    void prioritizesExplicitPlannerPageBindingsWithoutGuessingFromHeadingVocabulary() {
        PlannedSection planned = new PlannedSection(
                1,
                "orbiter-setup",
                "Orbiter supply",
                "Explain the orbiter supply.",
                true,
                true,
                List.of("orbiter supply"),
                List.of("components"),
                List.of(2, 3));
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
                "Image-only page",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {4}, 800, 1200)),
                RuleEvidence.ContentKind.VISUAL_PLACEHOLDER);

        assertThat(TeachingVisualEvidenceSelector.select(planned, List.of(imageOnly), true))
                .extracting(PageImageInput::pageNumber)
                .containsExactly(4);
        assertThat(TeachingVisualEvidenceSelector.select(planned, List.of(imageOnly), false)).isEmpty();
    }

    @Test
    void derivesAMultiImageBudgetFromTypedOwnedPagesInsteadOfTruncatingAtTwo() {
        PlannedSection planned = new PlannedSection(
                1,
                "turn-flow",
                "Turn flow",
                "Explain the full turn flow.",
                true,
                true,
                List.of("turn flow"),
                List.of("turn"),
                List.of(2, 3, 4, 5));
        UUID versionId = UUID.randomUUID();
        List<RuleEvidence> evidence = java.util.stream.IntStream.rangeClosed(2, 5)
                .mapToObj(page -> evidence(
                        versionId,
                        "Phase " + page,
                        page,
                        new RulePageImage(page, "image/jpeg", new byte[] {(byte) page}, 800, 1_200)))
                .toList();

        List<PageImageInput> selected = TeachingVisualEvidenceSelector.select(planned, evidence, true);

        assertThat(TeachingVisualEvidenceSelector.visualBudget(planned, evidence)).isEqualTo(4);
        assertThat(selected).extracting(PageImageInput::pageNumber).containsExactly(2, 3, 4, 5);
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
