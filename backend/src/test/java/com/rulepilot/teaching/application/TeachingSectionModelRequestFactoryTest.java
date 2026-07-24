package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingSectionModelRequestFactoryTest {

    @Test
    void attachesStoredVisualFactsAndTheRelevantSourcePageToTheModelRequest() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        TeachingPlan plan = plan(versionId);
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle of the table before the first turn.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1}, 1_000, 1_000)));
        TeachingSectionModelRequestFactory factory = new TeachingSectionModelRequestFactory(pageFacts(4));

        var request = factory.create(
                plan,
                plan.sections().getFirst(),
                new TeachingPacingPolicy.SectionPacing(60, 2),
                List.of(),
                List.of(evidence),
                true,
                true);

        assertThat(request.pageImages()).extracting(image -> image.pageNumber()).containsExactly(4);
        assertThat(request.evidence()).singleElement().satisfies(source ->
                assertThat(source.excerpt()).contains("Visible facts: The central board"));
        assertThat(request.chapterScope()).contains("【当前章节】第1章《开局准备》");
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "setup",
                        "开局准备",
                        "Explain how to place the central board before the first turn.",
                        true,
                        true,
                        List.of("central board setup"),
                        List.of("setup"))),
                "player",
                Instant.now());
    }

    private VisualRulebookPageFacts pageFacts(int pageNumber) {
        VisualRulebookPageFacts.PageFact fact = new VisualRulebookPageFacts.PageFact(
                pageNumber,
                "Central board",
                "The central board shows the shared setup area.",
                List.of("board"));
        return new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.contains(pageNumber) ? List.of(fact) : List.of();
            }
        };
    }
}
