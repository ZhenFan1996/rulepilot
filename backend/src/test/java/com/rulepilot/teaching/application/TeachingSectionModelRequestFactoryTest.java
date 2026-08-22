package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeachingSectionModelRequestFactoryTest {

    @Test
    void legacyPageLessUnitUsesTheAlreadyRetrievedEvidenceWithoutSubstringScoring() {
        UUID versionId = UUID.randomUUID();
        TeachingPlan.PlannedSection section = new TeachingPlan.PlannedSection(
                1,
                "legacy-flow",
                "旧计划流程",
                "按已检索证据讲清流程。",
                true,
                false,
                List.of("teaching-unit-v1.bGVnYWN5LXVuaXQ.b3BhcXVlLXNvdXJjZS1uYW1l"),
                List.of("source_coverage"),
                List.of(4));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, "Game", "Premise", List.of(section), "player", Instant.now());
        RuleEvidence evidence = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "完全不同的页标题", "这里是已经检索到的规则正文。", 4, 4);

        var request = new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty())
                .create(plan, section, List.of(), List.of(evidence), false, false);

        assertThat(request.teachingUnits()).singleElement().satisfies(unit -> {
            assertThat(unit.sourceIdentifiers()).containsExactly("opaque-source-name");
            assertThat(unit.directEvidenceIds()).containsExactly(evidence.chunkId());
        });
    }

    @Test
    void bindsAPlannedUnitToAllRetrievedChunksOnItsCanonicalAnchorPage() {
        UUID versionId = UUID.randomUUID();
        TeachingPlan.PlannedSection section = new TeachingPlan.PlannedSection(
                1,
                "flow",
                "执行流程",
                "按来源完成这一流程。",
                true,
                false,
                List.of(TeachingUnitContract.encode(
                        new TeachingUnitContract.Unit("flow-unit", Map.of("R-anchor", List.of(2))))),
                List.of("source_coverage"),
                List.of(2));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, "Game", "Premise", List.of(section), "player", Instant.now());
        RuleEvidence anchor = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "R-anchor", "R-anchor begins here.", 2, 2);
        RuleEvidence continuation = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Continuation", "The procedure continues here.", 2, 2);
        RuleEvidence unrelatedPage = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Other", "Another page's procedure.", 3, 3);
        RuleEvidence misleadingCrossReference = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "R-anchor", "See R-anchor on its canonical page.", 1, 1);

        var request = new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty())
                .create(
                        plan,
                        section,
                        List.of(),
                        List.of(misleadingCrossReference, anchor, continuation, unrelatedPage),
                        false,
                        false);

        assertThat(request.teachingUnits()).singleElement().satisfies(unit -> {
            assertThat(unit.sourceIdentifiers()).containsExactly("R-anchor");
            assertThat(unit.directEvidenceIds())
                    .containsExactly(anchor.chunkId(), continuation.chunkId())
                    .doesNotContain(misleadingCrossReference.chunkId(), unrelatedPage.chunkId());
        });
    }

    @Test
    void passesEverySourceInALargeAgentChosenUnitToTheSectionModel() {
        UUID versionId = UUID.randomUUID();
        List<String> identifiers = IntStream.rangeClosed(1, 38)
                .mapToObj(index -> "Reward relation " + index)
                .toList();
        Map<String, List<Integer>> sources = identifiers.stream().collect(java.util.stream.Collectors.toMap(
                identifier -> identifier,
                ignored -> List.of(8),
                (first, duplicate) -> first,
                java.util.LinkedHashMap::new));
        TeachingPlan.PlannedSection section = new TeachingPlan.PlannedSection(
                1,
                "reward-resolution",
                "结算完整奖励表",
                "按规则表逐项说明奖励结算。",
                true,
                false,
                List.of(TeachingUnitContract.encode(
                        new TeachingUnitContract.Unit("complete-reward-table", sources))),
                List.of("source_coverage"),
                List.of(8));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, "Game", "Premise", List.of(section), "player", Instant.now());
        RuleEvidence evidence = new RuleEvidence(
                UUID.randomUUID(), versionId, "REWARD", "Reward table", "The complete reward table.", 8, 8);

        var request = new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty())
                .create(plan, section, List.of(), List.of(evidence), false, false);

        assertThat(request.teachingUnits()).singleElement().satisfies(unit -> {
            assertThat(unit.unitId()).isEqualTo("complete-reward-table");
            assertThat(unit.sourceIdentifiers()).containsExactlyElementsOf(identifiers);
            assertThat(unit.directEvidenceIds()).containsExactly(evidence.chunkId());
        });
    }

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
        assertThat(request.evidence()).singleElement().satisfies(source -> {
            assertThat(source.excerpt())
                    .isEqualTo("Place the central board in the middle of the table before the first turn.")
                    .doesNotContain("The central board shows the shared setup area");
            assertThat(source.visualPresentation())
                        .contains("Visual presentation data only")
                        .contains("Cataloged visual anchors")
                        .contains("中央设置区")
                        .contains("rect=120,180,620,480");
        });
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

    @Test
    void everyChapterReceivesTheSameWholeGameModelButKeepsItsOwnUnitsAndEvidence() {
        UUID versionId = UUID.randomUUID();
        var alphaSlot = new com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft(
                "alpha-source",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole.SUPPORTING_RULE,
                "R-alpha",
                List.of(2),
                "observe-state",
                "observe-state-unit",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability.SOURCED);
        var betaSlot = new com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft(
                "beta-source",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole.SUPPORTING_RULE,
                "R-beta",
                List.of(3),
                "apply-change",
                "apply-change-unit",
                com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability.SOURCED);
        var wholeGame = new TeachingPlan.WholeGameContext(
                "先识别共享状态，再判断条件变化。",
                List.of(
                        new TeachingPlan.GlobalConcept(
                                "shared-state", "共享状态", "识别共同观察的状态。",
                                List.of("R-alpha"), List.of(2), List.of("observe-state"), List.of()),
                        new TeachingPlan.GlobalConcept(
                                "conditional-change", "条件变化", "判断状态何时改变。",
                                List.of("R-beta"), List.of(3), List.of("apply-change"), List.of("shared-state"))),
                List.of(new TeachingPlan.TopicDependency(
                        "observe-state", "apply-change", "先观察，后改变。")),
                true);
        var sections = List.of(
                new TeachingPlan.PlannedSection(
                        1, "observe-state", "观察状态", "识别状态。", true, false,
                        TeachingUnitContract.encodeUnits(List.of(alphaSlot)),
                        List.of(TeachingWholeGameUnderstandingPolicy.CONTRACT_TAG), List.of(2)),
                new TeachingPlan.PlannedSection(
                        2, "apply-change", "应用变化", "应用条件。", true, false,
                        TeachingUnitContract.encodeUnits(List.of(betaSlot)),
                        List.of(TeachingWholeGameUnderstandingPolicy.CONTRACT_TAG), List.of(3)));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, null, "Opaque system", "两章相互依赖。", wholeGame,
                sections, "player", Instant.now());
        RuleEvidence alpha = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Alpha", "R-alpha establishes state.", 2, 2);
        RuleEvidence beta = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Beta", "R-beta changes state.", 3, 3);
        TeachingSectionModelRequestFactory factory =
                new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty());

        var first = factory.create(plan, sections.getFirst(), List.of(), List.of(alpha), false, false);
        var second = factory.create(plan, sections.getLast(), List.of(), List.of(beta), false, false);

        assertThat(first.wholeGameContext()).isEqualTo(second.wholeGameContext());
        assertThat(first.wholeGameContext().evidenceBound()).isTrue();
        assertThat(first.wholeGameContext().concepts())
                .extracting(com.rulepilot.teaching.TeachingLessonModel.GlobalConceptInput::conceptId)
                .containsExactly("shared-state", "conditional-change");
        assertThat(first.teachingUnits()).extracting(unit -> unit.unitId())
                .containsExactly("observe-state-unit");
        assertThat(second.teachingUnits()).extracting(unit -> unit.unitId())
                .containsExactly("apply-change-unit");
        assertThat(first.evidence()).extracting(source -> source.chunkId()).containsExactly(alpha.chunkId());
        assertThat(second.evidence()).extracting(source -> source.chunkId()).containsExactly(beta.chunkId());
    }

    @Test
    void preservesEveryChapterObjectiveInLongAgentChosenLessonScope() {
        UUID versionId = UUID.randomUUID();
        List<TeachingPlan.PlannedSection> sections = IntStream.rangeClosed(1, 22)
                .mapToObj(position -> new TeachingPlan.PlannedSection(
                        position,
                        "topic-" + position,
                        "章节 " + position,
                        "第 " + position + " 章的完整教学目标：" + ("保留来源边界与必要例外。".repeat(32)),
                        true,
                        false,
                        List.of("rule-" + position),
                        List.of("source_coverage"),
                        List.of(position)))
                .toList();
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, "Game", "Premise", sections, "player", Instant.now());
        RuleEvidence evidence = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Rule one", "Rule one evidence.", 1, 1);

        var request = new TeachingSectionModelRequestFactory(VisualRulebookPageFacts.empty())
                .create(plan, sections.getFirst(), List.of(), List.of(evidence), false, false);

        assertThat(request.chapterScope()).hasSizeGreaterThan(4_000);
        assertThat(request.chapterScope())
                .contains("第22章《章节 22》")
                .contains(sections.getLast().objective());
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
