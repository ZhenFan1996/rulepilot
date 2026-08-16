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
                List.of(),
                List.of(evidence),
                true,
                true);

        assertThat(request.pageImages()).extracting(image -> image.pageNumber()).containsExactly(4);
        assertThat(request.modelConfigurationOwner()).isEqualTo("player");
        assertThat(request.evidence()).singleElement().satisfies(source ->
                assertThat(source.excerpt())
                        .contains("Visual presentation data only")
                        .contains("Cataloged visual anchors")
                        .contains("中央设置区")
                        .contains("rect=120,180,620,480")
                        .doesNotContain("The central board shows the shared setup area"));
        assertThat(request.chapterScope()).contains("【当前章节】第1章《开局准备》");
    }

    @Test
    void passesAllEightSourceInventoriedRuleGroupsToTheTeachingModel() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        List<String> ruleGroups = List.of(
                "move", "build", "trade", "copy", "recruit", "produce", "score", "pass");
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "actions",
                        "可执行行动",
                        "逐项讲清来源页上每一种行动。",
                        true,
                        false,
                        ruleGroups,
                        List.of("setup", "core_loop", "end", "scoring"))),
                "player",
                Instant.now());
        RuleEvidence evidence = new RuleEvidence(
                chunkId, versionId, "ACTIONS", "Available actions", "Choose one action.", 4, 4);

        var request = new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty()).create(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                false,
                false);

        assertThat(request.requiredRuleIntents()).containsExactlyElementsOf(ruleGroups);
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
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
                List.of("board"),
                List.of(new VisualRulebookPageFacts.VisualAnchor(
                        "table layout",
                        "中央设置区",
                        "主棋盘位于资源供应区和卡牌行之间。",
                        120,
                        180,
                        620,
                        480)));
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
