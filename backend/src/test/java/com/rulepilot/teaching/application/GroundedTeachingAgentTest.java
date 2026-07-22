package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class GroundedTeachingAgentTest {

    @Test
    void publishesTextFirstBaseLessonBeforeRunningOneBoundedWholeLessonReview() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1}, 1_086, 1_511)));
        AtomicInteger criticCalls = new AtomicInteger();
        List<IllustratedLesson> publications = new ArrayList<>();
        TeachingLessonModel model = request -> {
            assertThat(request.pageImages()).isEmpty();
            return new SectionDraft(
                    "立即可读的开局",
                    VisualKind.REFERENCE_CARD,
                    "把主棋盘放在桌面中央。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "放置主棋盘", TeachingMove.DO, "把主棋盘放在桌面中央。", List.of(chunkId))));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                model,
                new PolicyEvidenceVerifier(),
                (request, risk) -> {
                    criticCalls.incrementAndGet();
                    assertThat(publications).isNotEmpty();
                    assertThat(publications.getLast().status()).isEqualTo(LessonStatus.DRAFT_READY);
                    assertThat(request.reviewMode()).isEqualTo(GeneratedContentCritic.ReviewMode.POST_PUBLICATION);
                    return new GeneratedContentCritic.Review(true, List.of());
                },
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, publications::add);

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(criticCalls).hasValue(1);
    }

    @Test
    void givesImageOnlyRulebookPagesToTheBaseLessonModel() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualOnlyEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "GENERAL",
                "Visual rulebook page 4",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                assertThat(request.pageImages()).extracting(PageImageInput::pageNumber).containsExactly(4);
                return new SectionDraft(
                        "立即可读的开局",
                        VisualKind.TABLE_LAYOUT,
                        "依据规则书页面完成开局。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "找到主棋盘",
                                TeachingMove.VISUAL,
                                "在图中找到主棋盘，再把它放在桌面中央。",
                                List.of(chunkId),
                                new VisualFocusDraft(4, "主棋盘", 150, 150, 500, 500))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualOnlyEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
    }

    @Test
    void writesAnInlineVisualStepForATopicThePlanMarksAsVisuallyNecessary() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "COMPONENTS",
                "Card anatomy",
                "The cost and effect are printed beside their icons.",
                5,
                5,
                List.of(new RulePageImage(5, "image/jpeg", new byte[] {1, 2}, 1_086, 1_511)));
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                assertThat(request.pageImages()).extracting(PageImageInput::pageNumber).containsExactly(5);
                return new SectionDraft(
                        "看懂卡牌图标",
                        VisualKind.TABLE_LAYOUT,
                        "对照卡牌上的费用与效果。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "找到费用与效果",
                                TeachingMove.VISUAL,
                                "先找到费用图标，再看旁边的效果。",
                                List.of(chunkId),
                                new VisualFocusDraft(5, "费用与效果图标", 120, 180, 520, 260))));
            }
        };
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        "card-anatomy",
                        "卡牌结构",
                        "看懂卡牌费用与效果图标。",
                        true,
                        true,
                        List.of("Card anatomy"),
                        List.of("components"),
                        List.of(5))),
                "player",
                Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections().getFirst().steps().getFirst().visualFocus())
                .isEqualTo(new IllustratedLesson.VisualFocus(5, "费用与效果图标", 120, 180, 520, 260));
    }

    @Test
    void replacesAnEnglishVisualCropLabelWithTheChineseStepHeading() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1}, 1_086, 1_511)));
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                return new SectionDraft(
                        "照图完成开局",
                        VisualKind.TABLE_LAYOUT,
                        "找到桌面中央的主棋盘。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "找到主棋盘",
                                TeachingMove.VISUAL,
                                "在图中找到主棋盘，再把它放在桌面中央。",
                                List.of(chunkId),
                                new VisualFocusDraft(4, "Gameplay Overview Diagram", 160, 180, 500, 420))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections().getFirst().steps().getFirst().visualFocus().label()).isEqualTo("找到主棋盘");
    }

    @Test
    void turnsPersistedVisualPageFactsIntoCitedLessonEvidence() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence pageEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "GENERAL",
                "Visual rulebook page 4",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of(pageEvidence);
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId, java.util.Set<Integer> pages, boolean includePageImages) {
                return List.of(pageEvidence);
            }
        };
        VisualRulebookPageFacts facts = new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID documentVersionId, java.util.Set<Integer> pages) {
                return List.of(new PageFact(
                        4,
                        "Overpopulation: 3 of the same Wildlife Token",
                        "3 个相同动物标记时，当前玩家可以清除这 3 个标记；每回合只能这样做一次。",
                        List.of("Overpopulation", "Wildlife Token")));
            }
        };
        TeachingLessonModel model = request -> {
            assertThat(request.evidence()).singleElement().extracting(TeachingLessonModel.EvidenceInput::excerpt)
                    .asString()
                    .contains("每回合只能这样做一次");
            return new SectionDraft(
                    "种群过剩",
                    VisualKind.FLOW_DIAGRAM,
                    "查看同类动物标记。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "每回合一次",
                            TeachingMove.WATCH,
                            "出现 3 个相同动物标记时，当前玩家可以清除这 3 个标记；每回合只能这样做一次。",
                            List.of(chunkId))));
        };
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        "overpopulation",
                        "种群过剩",
                        "Explain overpopulation",
                        true,
                        true,
                        List.of("overpopulation"),
                        List.of("core_loop"),
                        List.of(4))),
                "player",
                Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                facts,
                4,
                1);

        IllustratedLesson lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections()).singleElement().satisfies(section -> assertThat(section.evidenceStatus())
                .isEqualTo(EvidenceStatus.SUPPORTED));
    }

    @Test
    void interpretsAnUncatalogedRequiredVisualPageBeforeWritingTheLesson() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence pageEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "ACTION",
                "Visual rulebook page 14",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                14,
                14,
                List.of(new RulePageImage(14, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AssistantReadTools tools = new AssistantReadTools() {
            @Override
            public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
                return List.of(pageEvidence);
            }

            @Override
            public List<RuleEvidence> readRuleEvidencePages(
                    UUID documentVersionId, java.util.Set<Integer> pages, boolean includePageImages) {
                return List.of(pageEvidence);
            }
        };
        AtomicInteger catalogCalls = new AtomicInteger();
        VisualRulebookPageCatalogModel catalog = request -> {
            catalogCalls.incrementAndGet();
            assertThat(request.pages()).extracting(com.rulepilot.teaching.TeachingOutlineModel.PageImageInput::pageNumber)
                    .containsExactly(14);
            assertThat(request.rulebookTitle()).isEqualTo("Game");
            return new VisualRulebookPageCatalogModel.CatalogDraft(List.of(new VisualRulebookPageCatalogModel.PageSummary(
                    14,
                    "KODORA; victory point token",
                    "KODORA只能在至少拥有2个胜利点时使用；把2个胜利点放在KODORA上。",
                    List.of("KODORA", "victory point token"))));
        };
        TeachingLessonModel model = request -> {
            assertThat(request.evidence()).singleElement().extracting(TeachingLessonModel.EvidenceInput::excerpt)
                    .asString()
                    .contains("至少拥有2个胜利点");
            return new SectionDraft(
                    "KODORA行动",
                    VisualKind.REFERENCE_CARD,
                    "先确认胜利点数量，再放置2个胜利点。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "支付胜利点",
                            TeachingMove.DO,
                            "拥有至少2个胜利点时，把2个胜利点放在KODORA上。",
                            List.of(chunkId))));
        };
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        "kodora",
                        "KODORA行动",
                        "Explain KODORA",
                        true,
                        true,
                        List.of("KODORA"),
                        List.of("actions"),
                        List.of(14))),
                "player",
                Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty(),
                catalog,
                4,
                1);

        IllustratedLesson lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(catalogCalls).hasValue(1);
        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).contains("2个胜利点");
    }

    @Test
    void addsCrossPageVisualFactsToTextEvidenceWithMissingInlineIcons() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence textEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SPECIAL_RULE",
                "KODORA",
                "Use KODORA only if you have at least 2  . Place 2  on it.",
                14,
                14);
        VisualRulebookPageFacts facts = new VisualRulebookPageFacts() {
            @Override
            public void replace(UUID documentVersionId, List<PageFact> pages) {}

            @Override
            public List<PageFact> find(UUID documentVersionId, java.util.Set<Integer> pages) {
                return List.of(new PageFact(
                        14,
                        "KODORA; victory point token",
                        "第7页图例标明红色图标是胜利点；第14页KODORA要求放置2个胜利点。",
                        List.of("KODORA", "victory point token")));
            }
        };
        TeachingLessonModel model = request -> {
            assertThat(request.pageImages()).isEmpty();
            assertThat(request.evidence()).singleElement().extracting(TeachingLessonModel.EvidenceInput::excerpt)
                    .asString().contains("KODORA要求放置2个胜利点");
            return new SectionDraft(
                    "KODORA下注",
                    VisualKind.REFERENCE_CARD,
                    "KODORA使用胜利点下注。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "放置胜利点",
                            TeachingMove.DO,
                            "把KODORA放在屏风前，并在上面放2个胜利点。",
                            List.of(chunkId))));
        };
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        "kodora",
                        "KODORA",
                        "Teach the KODORA wager.",
                        true,
                        false,
                        List.of("KODORA"),
                        List.of("exceptions"),
                        List.of(14))),
                "player",
                Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(textEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                facts,
                4,
                1);

        IllustratedLesson lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections().getFirst().steps().getFirst().text()).contains("2个胜利点");
    }

    @Test
    void generatesPostFirstBaseSectionsConcurrentlyAndPublishesOnlyContiguousReadingOrder() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AtomicInteger activeCompositions = new AtomicInteger();
        AtomicInteger peakCompositions = new AtomicInteger();
        TeachingLessonModel model = request -> {
            int active = activeCompositions.incrementAndGet();
            peakCompositions.accumulateAndGet(active, Math::max);
            try {
                if (!request.topicKey().equals(TeachingSectionType.OBJECTIVE.name())) Thread.sleep(60);
                return new SectionDraft(
                        request.title(),
                        VisualKind.REFERENCE_CARD,
                        "按引用完成这一节。",
                        List.of(chunkId),
                        List.of(new StepDraft("照着做", TeachingMove.DO, "按引用完成这一节。", List.of(chunkId))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                activeCompositions.decrementAndGet();
            }
        };
        List<IllustratedLesson> publications = new ArrayList<>();
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                12,
                2);

        IllustratedLesson lesson = agent.createBase(continuityPlan(versionId), UUID.randomUUID(), null, publications::add);

        assertThat(peakCompositions.get()).isGreaterThanOrEqualTo(2);
        assertThat(lesson.sections()).extracting(LessonSection::position).containsExactly(1, 2, 3);
        assertThat(publications).allSatisfy(snapshot -> assertThat(snapshot.sections())
                .extracting(LessonSection::position)
                .containsSequence(java.util.stream.IntStream.rangeClosed(1, snapshot.sections().size())
                        .boxed().toArray(Integer[]::new)));
    }

    @Test
    void repairs_a_player_step_that_ends_in_an_incomplete_ellipsis() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return oneStepDraft(chunkId, "交易后你手上有2个木头和……完成了。");
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().satisfies(value -> assertThat(value).contains("do not end a rule"));
                return oneStepDraft(chunkId, "交易后你手上有2个木头；如果仍不够建造，就保留资源等下一回合。");
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, ignored -> {});

        assertThat(revisions).hasValue(1);
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).doesNotContain("……");
    }

    @Test
    void repairsASetupCheckThatClaimsDrawnTokensStillAllRemainInTheBag() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return setupInventoryDraft(chunkId, "中央摆好4个标记；布袋里有所有野生动物标记。");
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString().contains("remaining supply");
                return setupInventoryDraft(chunkId, "中央摆好4个标记；其余野生动物标记留在布袋里。");
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, ignored -> {});

        assertThat(revisions).hasValue(1);
        assertThat(lesson.sections().getFirst().steps().getLast().text()).contains("其余").doesNotContain("所有");
    }

    @Test
    void removesEnglishQuestionFillerWithoutDroppingRetrievalConditions() {
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(),
                request -> null,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        assertThat(agent.focusedRetrievalQuery(
                        "What is the cost to launch a probe and what is the default limit on probes in space?"))
                .isEqualTo("cost launch probe default limit on probes in space");
        assertThat(agent.focusedRetrievalQuery(
                        "When landing on a planet that already has an orbiter, what is the cost reduction?"))
                .isEqualTo("landing on planet that already has orbiter cost reduction");
    }

    @Test
    void reusesPreviouslyVerifiedTopicsAndRetriesOnlyIncompleteOnes() {
        UUID versionId = UUID.randomUUID();
        TeachingPlan plan = plan(versionId);
        LessonSection verified = new LessonSection(
                1,
                TeachingSectionType.SETUP.name(),
                List.of("setup"),
                "已验证的开局",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.TABLE_LAYOUT,
                "桌面布置",
                List.of(2),
                List.of(UUID.randomUUID()),
                List.of(new LessonStep(1, "将棋盘放在桌面中央。", List.of(2), List.of(UUID.randomUUID()))));
        IllustratedLesson previous = new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                LessonStatus.COMPLETE,
                List.of(verified),
                GroundedTeachingAgent.GENERATOR_VERSION,
                Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> {
                    throw new AssertionError("verified topic must not be retrieved again");
                },
                request -> {
                    throw new AssertionError("verified topic must not be regenerated");
                },
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var resumed = agent.create(plan, UUID.randomUUID(), previous);

        assertThat(resumed.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(resumed.sections()).containsExactly(verified);
    }

    @Test
    void publishesTheStableLessonImmediatelyWhenAReusableChapterCompletesIt() {
        UUID versionId = UUID.randomUUID();
        TeachingPlan plan = plan(versionId);
        LessonSection verified = new LessonSection(
                1,
                TeachingSectionType.SETUP.name(),
                List.of("setup"),
                "已验证的开局",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.TABLE_LAYOUT,
                "桌面布置",
                List.of(2),
                List.of(UUID.randomUUID()),
                List.of(new LessonStep(1, "将棋盘放在桌面中央。", List.of(2), List.of(UUID.randomUUID()))));
        IllustratedLesson previous = new IllustratedLesson(
                UUID.randomUUID(), plan.id(), LessonStatus.COMPLETE, List.of(verified),
                GroundedTeachingAgent.GENERATOR_VERSION, Instant.now());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> { throw new AssertionError("verified topic must not be retrieved again"); },
                request -> { throw new AssertionError("verified topic must not be regenerated"); },
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);
        List<IllustratedLesson> publications = new ArrayList<>();

        IllustratedLesson completed = agent.create(plan, UUID.randomUUID(), previous, publications::add);

        assertThat(publications).hasSize(1);
        assertThat(publications.getFirst().status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(publications.getFirst().sections()).containsExactly(verified);
        assertThat(publications.getFirst()).isEqualTo(completed);
        assertThat(publications).extracting(IllustratedLesson::id).containsOnly(completed.id());
    }

    @Test
    void regeneratesPreviouslyVerifiedVisualTopicThatNeverUsedItsPageImage() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        TeachingPlan plan = plan(versionId);
        LessonSection proseOnly = new LessonSection(
                1,
                TeachingSectionType.SETUP.name(),
                List.of("setup"),
                "已验证但没有看图的开局",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.TABLE_LAYOUT,
                "桌面布置",
                List.of(4),
                List.of(chunkId),
                List.of(new LessonStep(1, "放置主棋盘", TeachingMove.DO, "将棋盘放在桌面中央。", List.of(4), List.of(chunkId), null)));
        IllustratedLesson previous = new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                LessonStatus.COMPLETE,
                List.of(proseOnly),
                GroundedTeachingAgent.GENERATOR_VERSION,
                Instant.now());
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Assemble the main board and place it in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {4}, 1_086, 1_511)));
        AtomicInteger compositions = new AtomicInteger();
        TeachingLessonModel visionModel = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                compositions.incrementAndGet();
                return new SectionDraft(
                        "照图完成开局",
                        VisualKind.TABLE_LAYOUT,
                        "找到拼接后的主棋盘。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "找到主棋盘",
                                TeachingMove.VISUAL,
                                "在图中找到拼接后的主棋盘，再按同样关系摆放。",
                                List.of(chunkId),
                                new VisualFocusDraft(4, "拼接后的主棋盘", 300, 100, 600, 700))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                visionModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var resumed = agent.create(plan, UUID.randomUUID(), previous);

        assertThat(compositions).hasValue(1);
        assertThat(resumed.sections().getFirst().title()).isEqualTo("照图完成开局");
        assertThat(resumed.sections().getFirst().steps().getFirst().visualFocus()).isNotNull();
    }

    @Test
    void retrievesVersionScopedEvidenceAndPersistsValidatedStepCitations() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        AtomicInteger retrievalCalls = new AtomicInteger();
        AssistantReadTools tools = request -> {
            assertThat(request.documentVersionId()).isEqualTo(versionId);
            assertThat(request.includeAdjacentContext()).isTrue();
            if (retrievalCalls.getAndIncrement() == 0) {
                assertThat(request.sectionTypes()).isEmpty();
            } else {
                assertThat(request.sectionTypes()).isEmpty();
                assertThat(request.query()).containsIgnoringCase("setup");
            }
            return List.of(evidence);
        };
        TeachingLessonModel model = request -> {
            assertThat(request.totalDurationMinutes()).isEqualTo(20);
            assertThat(request.sectionDurationSeconds()).isEqualTo(1_200);
            assertThat(request.maxSteps()).isEqualTo(6);
            assertThat(request.priorSections()).isEmpty();
            assertThat(request.requiredRuleIntents()).containsExactly("SETUP", "More SETUP");
            return new TeachingLessonModel.SectionDraft(
                    "三步完成开局",
                    VisualKind.TABLE_LAYOUT,
                    "桌面布置示意",
                    List.of(chunkId),
                    List.of(new TeachingLessonModel.StepDraft(
                            "摆放主棋盘",
                            TeachingMove.DO,
                            "将棋盘放在桌面中央。",
                            List.of(chunkId))));
        };
        AtomicInteger criticCalls = new AtomicInteger();
        RecordingInvocations invocations = new RecordingInvocations();
        GeneratedContentCritic critic = (request, risk) -> {
            criticCalls.incrementAndGet();
            assertThat(request.reviewMode()).isEqualTo(GeneratedContentCritic.ReviewMode.POST_PUBLICATION);
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            assertThat(request.taskContext().objective()).contains("SETUP");
            assertThat(request.taskContext().requiredCoverage()).contains("setup");
            assertThat(request.claims()).hasSize(2);
            assertThat(request.claims().getFirst().text()).isEqualTo("第1章「SETUP」：桌面布置示意");
            assertThat(request.claims().getFirst().citationIds()).containsExactly(chunkId);
            assertThat(request.claims().get(1).text())
                    .isEqualTo("第1章「SETUP」：摆放主棋盘：将棋盘放在桌面中央。");
            return new GeneratedContentCritic.Review(true, List.of());
        };
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        tools, model, new PolicyEvidenceVerifier(), critic,
                        invocations, 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(lesson.sections().getFirst().visualSourcePages()).containsExactly(2, 3);
        assertThat(lesson.sections().getFirst().visualSourceChunkIds()).containsExactly(chunkId);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourcePages()).containsExactly(2, 3);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds()).containsExactly(chunkId);
        assertThat(lesson.sections().getFirst().steps().getFirst().heading()).isEqualTo("摆放主棋盘");
        assertThat(lesson.sections().getFirst().steps().getFirst().kind()).isEqualTo(TeachingMove.DO);
        assertThat(retrievalCalls).hasValue(3);
        assertThat(criticCalls).hasValue(1);
        assertThat(invocations.diagnostics).containsExactly(
                new Diagnostic("validateTeachingSection|1|0", ActivityOutcome.SUCCEEDED,
                        "Teaching draft accepted: CITED_DRAFT_ACCEPTED"),
                new Diagnostic("publishTeachingSection|1", ActivityOutcome.SUCCEEDED,
                        "Teaching section published: CITED_DRAFT_PUBLISHED"),
                new Diagnostic("validateTeachingSection|1|0", ActivityOutcome.SUCCEEDED,
                        "Teaching draft accepted: POST_PUBLICATION_REVIEW_ACCEPTED"),
                new Diagnostic("publishTeachingSection|1", ActivityOutcome.SUCCEEDED,
                        "Teaching section published: POST_PUBLICATION_REVIEW_ACCEPTED"));
    }

    @Test
    void keepsACompleteCitedDraftReadableWhenPostPublicationReviewCannotContinue() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        List<IllustratedLesson> publications = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        TeachingLessonModel model = request -> {
            modelCalls.incrementAndGet();
            return new SectionDraft(
                    "先完成可玩的开局",
                    VisualKind.REFERENCE_CARD,
                    "把主棋盘放在桌面中央。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "摆放主棋盘", TeachingMove.DO, "把主棋盘放在桌面中央。", List.of(chunkId))));
        };
        GeneratedContentCritic unavailableReview = (request, risk) -> {
            assertThat(publications).isNotEmpty();
            assertThat(publications.getLast().status()).isEqualTo(LessonStatus.DRAFT_READY);
            assertThat(publications.getLast().sections().getFirst().evidenceStatus())
                    .isEqualTo(EvidenceStatus.CITED_DRAFT);
            throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                model,
                new PolicyEvidenceVerifier(),
                unavailableReview,
                new ImmediateAuditedAgentInvocations(),
                4);

        IllustratedLesson lesson = agent.create(plan(versionId), UUID.randomUUID(), null, publications::add);

        assertThat(modelCalls).hasValue(1);
        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void sendsOnlyDraftCitedEvidenceToTheCritic() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence cited = evidence(UUID.randomUUID(), versionId);
        RuleEvidence unrelated = new RuleEvidence(
                UUID.randomUUID(), versionId, "SCORING", "Scoring", "Each coin scores a point.", 9, 9);
        TeachingLessonModel model = request -> new SectionDraft(
                "三步完成开局",
                VisualKind.REFERENCE_CARD,
                "把主棋盘放在桌面中央。",
                List.of(cited.chunkId()),
                List.of(new StepDraft(
                        "摆放主棋盘", TeachingMove.DO, "把主棋盘放在桌面中央。", List.of(cited.chunkId()))));
        GeneratedContentCritic critic = (request, risk) -> {
            assertThat(request.reviewMode()).isEqualTo(GeneratedContentCritic.ReviewMode.POST_PUBLICATION);
            assertThat(request.evidence()).extracting(GeneratedContentCritic.Evidence::chunkId)
                    .containsExactly(cited.chunkId(), unrelated.chunkId());
            return new GeneratedContentCritic.Review(true, List.of());
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(cited, unrelated),
                model,
                new PolicyEvidenceVerifier(),
                critic,
                new ImmediateAuditedAgentInvocations(),
                4);

        assertThat(agent.create(plan(versionId), UUID.randomUUID()).status()).isEqualTo(LessonStatus.COMPLETE);
    }

    @Test
    void revisesAnyEvidenceSupportedAlternativeOmittedFromTheObjective() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence river = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "River route",
                "Move the boat along a connected river route.",
                8,
                8);
        RuleEvidence tunnel = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Tunnel route",
                "After lighting a lantern, the boat may use a connected tunnel route.",
                9,
                9);
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return new SectionDraft(
                        "移动船只",
                        VisualKind.REFERENCE_CARD,
                        "让船沿相连的河道移动。",
                        List.of(river.chunkId()),
                        List.of(new StepDraft(
                                "走河道", TeachingMove.DO, "让船沿相连的河道移动。", List.of(river.chunkId()))));
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anyMatch(message -> message.contains(tunnel.chunkId().toString()));
                return new SectionDraft(
                        "移动船只",
                        VisualKind.REFERENCE_CARD,
                        "让船沿相连的河道移动。",
                        List.of(river.chunkId()),
                        List.of(
                                new StepDraft(
                                        "走河道",
                                        TeachingMove.DO,
                                        "让船沿相连的河道移动。",
                                        List.of(river.chunkId())),
                                new StepDraft(
                                        "点灯后走隧道",
                                        TeachingMove.WATCH,
                                        "点亮提灯后，船也可以沿相连的隧道移动。",
                                        List.of(tunnel.chunkId()))));
            }
        };
        AtomicInteger reviewCalls = new AtomicInteger();
        List<String> retrievalQueries = new ArrayList<>();
        GeneratedContentCritic critic = (request, risk) -> {
            assertThat(request.reviewMode()).isEqualTo(GeneratedContentCritic.ReviewMode.POST_PUBLICATION);
            reviewCalls.incrementAndGet();
            boolean tunnelCovered = request.claims().stream().anyMatch(claim -> claim.text().contains("隧道"));
            return tunnelCovered
                    ? new GeneratedContentCritic.Review(true, List.of())
                    : new GeneratedContentCritic.Review(true, List.of(new Issue(
                            IssueType.MISSING_CRITICAL_RULE,
                            1,
                            List.of(tunnel.chunkId()),
                            "The evidenced tunnel route is missing from this chapter.")));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> {
                    retrievalQueries.add(request.query());
                    return List.of(river, tunnel);
                },
                model,
                new PolicyEvidenceVerifier(),
                critic,
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(alternativeRoutePlan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(revisions).hasValue(1);
        assertThat(reviewCalls).hasValue(1);
        assertThat(retrievalQueries).anyMatch(query -> query.contains("tunnel route"));
        assertThat(lesson.sections().getFirst().steps())
                .extracting(LessonStep::text)
                .anyMatch(text -> text.contains("隧道"));
    }

    @Test
    void preservesAgentSelectedDynamicTeachingBlockKind() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(evidence(chunkId, versionId));
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "按顺序完成开局",
                VisualKind.FLOW_DIAGRAM,
                "沿着规则书中的开局顺序检查桌面。",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft(
                        "开局流程",
                        TeachingMove.FLOW,
                        "先放置版图，再领取起始组件。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.sections().getFirst().steps().getFirst().kind()).isEqualTo(TeachingMove.FLOW);
        assertThat(lesson.generatorVersion()).isEqualTo(GroundedTeachingAgent.GENERATOR_VERSION);
    }

    @Test
    void derivesMissingPresentationMetadataFromAnEvidenceCitedTeachingStep() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(evidence(chunkId, versionId));
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "探测行动",
                VisualKind.REFERENCE_CARD,
                null,
                List.of(),
                List.of(new TeachingLessonModel.StepDraft(
                        "执行探测",
                        TeachingMove.DO,
                        "选择一项有证据支持的探测行动并结算。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().visualCaption())
                .isEqualTo("选择一项有证据支持的探测行动并结算。");
        assertThat(lesson.sections().getFirst().visualSourceChunkIds()).containsExactly(chunkId);
    }

    @Test
    void fallsBackToCompleteTextWhenVisualFocusCannotBeValidated() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Assemble the main board and place it in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AtomicInteger visualCompositions = new AtomicInteger();
        AtomicInteger textCompositions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                if (!request.pageImages().isEmpty()) {
                    visualCompositions.incrementAndGet();
                    assertThat(request.pageImages()).extracting(PageImageInput::pageNumber).containsExactly(4);
                    return new SectionDraft(
                            "照图拼好主棋盘",
                            VisualKind.TABLE_LAYOUT,
                            "主棋盘由三块弧形板拼接后放在桌面中央。",
                            List.of(chunkId),
                            List.of(new StepDraft(
                                    "找到三块主板",
                                    TeachingMove.VISUAL,
                                    "先在图中找到拼接后的主棋盘，再按同样关系摆到桌面中央。",
                                    List.of(chunkId),
                                    new VisualFocusDraft(3, "拼接后的主棋盘", 900, 990, 300, 120))));
                }
                textCompositions.incrementAndGet();
                return new SectionDraft(
                        "照着文字完成开局",
                        VisualKind.REFERENCE_CARD,
                        "把主棋盘放到桌面中央。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "摆放主棋盘",
                                TeachingMove.DO,
                                "把主棋盘放到桌面中央。",
                                List.of(chunkId))));
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                assertThat(request.pageImages()).isNotEmpty();
                return compose(request);
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps()).singleElement().satisfies(step -> {
            assertThat(step.kind()).isEqualTo(TeachingMove.DO);
            assertThat(step.visualFocus()).isNull();
        });
        assertThat(visualCompositions).hasValue(2);
        assertThat(textCompositions).hasValue(1);
    }

    @Test
    void fallsBackToCitedTextWhenVisualCompositionIsUnavailable() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Assemble the main board and place it in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AtomicInteger visualCompositions = new AtomicInteger();
        AtomicInteger textCompositions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                if (!request.pageImages().isEmpty()) {
                    visualCompositions.incrementAndGet();
                    throw new IllegalStateException("vision provider unavailable");
                }
                textCompositions.incrementAndGet();
                return new SectionDraft(
                        "照着文字完成开局",
                        VisualKind.REFERENCE_CARD,
                        "把主棋盘放到桌面中央。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "摆放主棋盘",
                                TeachingMove.DO,
                                "把主棋盘放到桌面中央。",
                                List.of(chunkId))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps().getFirst().visualFocus()).isNull();
        assertThat(visualCompositions).hasValue(1);
        assertThat(textCompositions).hasValue(1);
    }

    @Test
    void fallsBackToCitedTextWhenVisualRepairIsUnavailable() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Assemble the main board and place it in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AtomicInteger visualCompositions = new AtomicInteger();
        AtomicInteger textCompositions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                if (!request.pageImages().isEmpty()) {
                    visualCompositions.incrementAndGet();
                    return new SectionDraft(
                            "照图拼好主棋盘",
                            VisualKind.TABLE_LAYOUT,
                            "主棋盘放在桌面中央。",
                            List.of(chunkId),
                            List.of(new StepDraft(
                                    "找到主棋盘",
                                    TeachingMove.VISUAL,
                                    "在图中找到主棋盘。",
                                    List.of(chunkId),
                                    new VisualFocusDraft(3, "主棋盘", 900, 990, 300, 120))));
                }
                textCompositions.incrementAndGet();
                return new SectionDraft(
                        "照着文字完成开局",
                        VisualKind.REFERENCE_CARD,
                        "把主棋盘放到桌面中央。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "摆放主棋盘",
                                TeachingMove.DO,
                                "把主棋盘放到桌面中央。",
                                List.of(chunkId))));
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                throw new IllegalStateException("vision provider unavailable");
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps().getFirst().visualFocus()).isNull();
        assertThat(visualCompositions).hasValue(1);
        assertThat(textCompositions).hasValue(1);
    }

    @Test
    void preservesValidatedTextFallbackPresentationMetadataWhenARevisionDropsIt() {
        UUID chunkId = UUID.randomUUID();
        SectionDraft previous = new SectionDraft(
                "原章节",
                VisualKind.REFERENCE_CARD,
                "按引用完成这一节。",
                List.of(chunkId),
                List.of(new StepDraft("原步骤", TeachingMove.DO, "按引用完成这一节。", List.of(chunkId))));
        StepDraft revisedStep = new StepDraft(
                "修订步骤", TeachingMove.DO, "修订后的规则文字。", List.of(chunkId));
        SectionDraft revised = new SectionDraft(
                "修订章节", null, "", List.of(), List.of(revisedStep));

        SectionDraft preserved = GroundedTeachingAgent.preserveTextOnlyPresentationMetadata(previous, revised);

        assertThat(preserved.visualKind()).isEqualTo(VisualKind.REFERENCE_CARD);
        assertThat(preserved.visualCaption()).isEqualTo("按引用完成这一节。");
        assertThat(preserved.visualCitationIds()).containsExactly(chunkId);
        assertThat(preserved.steps()).containsExactly(revisedStep);
    }

    @Test
    void withholdsStoredPageImagesFromATextOnlyTeachingModel() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1}, 1_086, 1_511)));
        TeachingLessonModel textOnlyModel = request -> {
            assertThat(request.pageImages()).isEmpty();
            return new TeachingLessonModel.SectionDraft(
                    "开局位置",
                    VisualKind.REFERENCE_CARD,
                    "把主棋盘放在桌面中央。",
                    List.of(chunkId),
                    List.of(new TeachingLessonModel.StepDraft(
                            "放置主棋盘", TeachingMove.DO, "把主棋盘放在桌面中央。", List.of(chunkId))));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                textOnlyModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps().getFirst().visualFocus()).isNull();
    }

    @Test
    void normalizesUnresolvedPdfIconMarkersBeforeTextFallbackValidation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Starting dice",
                "Place the [BOOST] die beside your player board.",
                3,
                3,
                List.of());
        TeachingLessonModel textOnlyModel = request -> new TeachingLessonModel.SectionDraft(
                "准备 [BOOST] 与 👣 骰子",
                VisualKind.REFERENCE_CARD,
                "把 [BOOST] 骰子和 👣 图标放在玩家板旁。",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft(
                        "摆放 [BOOST] 与 👣 图标",
                        TeachingMove.DO,
                        "把 [BOOST] 骰子和 👣 图标放在玩家板旁。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                textOnlyModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().title()).isEqualTo("准备 “BOOST”图标 与 “脚印（移动）”图标 骰子");
        assertThat(lesson.sections().getFirst().steps().getFirst().text())
                .isEqualTo("把 “BOOST”图标 骰子和 “脚印（移动）”图标放在玩家板旁。");
    }

    @Test
    void removesLeadingInternalEvidenceLanguageBeforeTeachingValidation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Starting dice",
                "Place the boost die beside your player board.",
                3,
                3,
                List.of());
        TeachingLessonModel textOnlyModel = request -> new TeachingLessonModel.SectionDraft(
                "起始骰子",
                VisualKind.REFERENCE_CARD,
                "当前证据显示：把加速骰子放在玩家板旁。",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft(
                        "摆放加速骰子",
                        TeachingMove.DO,
                        "根据已提供的证据，把加速骰子放在玩家板旁。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                textOnlyModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().visualCaption()).isEqualTo("把加速骰子放在玩家板旁。");
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).isEqualTo("把加速骰子放在玩家板旁。");
    }

    @Test
    void restoresThePlannedTitleWhenTheModelReturnsAnInvalidSectionTitle() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the center of the table.",
                3,
                3,
                List.of());
        TeachingLessonModel textOnlyModel = request -> new TeachingLessonModel.SectionDraft(
                " ",
                VisualKind.REFERENCE_CARD,
                "把主棋盘放到桌面中央。",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft(
                        "摆放主棋盘",
                        TeachingMove.DO,
                        "把主棋盘放到桌面中央。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                textOnlyModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().title()).isEqualTo("SETUP");
    }

    @Test
    void withholdsPageImagesWhenTheOutlineAgentSaysProseIsSufficient() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence pageBackedEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Round end",
                "Advance the round marker after every player has passed.",
                7,
                7,
                List.of(new RulePageImage(7, "image/jpeg", new byte[] {7}, 1_086, 1_511)));
        TeachingLessonModel visionModel = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                assertThat(request.pageImages()).isEmpty();
                return new SectionDraft(
                        "一轮如何结束",
                        VisualKind.FLOW_DIAGRAM,
                        "所有玩家跳过后推进轮次标记。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "推进轮次",
                                TeachingMove.FLOW,
                                "所有玩家都跳过后，将轮次标记推进一格。",
                                List.of(chunkId))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(pageBackedEvidence),
                visionModel,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(nonVisualPlan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
    }

    @Test
    void ranksPagesByTopicEvidenceCoverageInsteadOfFirstImageOrder() {
        UUID versionId = UUID.randomUUID();
        RulePageImage componentPage = new RulePageImage(2, "image/jpeg", new byte[] {2}, 1_086, 1_511);
        RulePageImage setupPage = new RulePageImage(4, "image/jpeg", new byte[] {4}, 1_086, 1_511);
        RuleEvidence firstComponent = new RuleEvidence(
                UUID.randomUUID(), versionId, "COMPONENTS", "Components", "Main board pieces.", 2, 2,
                List.of(componentPage));
        RuleEvidence setupLayout = new RuleEvidence(
                UUID.randomUUID(), versionId, "SETUP", "Setup main board", "Assemble the main board.", 4, 4,
                List.of(setupPage));
        RuleEvidence setupPlacement = new RuleEvidence(
                UUID.randomUUID(), versionId, "SETUP", "Setup table", "Place it in the middle.", 4, 4,
                List.of(setupPage));
        List<Integer> reviewedClaimCounts = new ArrayList<>();
        TeachingLessonModel visionModel = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                assertThat(request.pageImages()).extracting(PageImageInput::pageNumber).containsExactly(4, 2);
                assertThat(request.maxSteps()).isEqualTo(6);
                return new SectionDraft(
                        "完成开局",
                        VisualKind.TABLE_LAYOUT,
                        "组装主棋盘并放到桌面中央。",
                        List.of(setupLayout.chunkId(), setupPlacement.chunkId()),
                        List.of(new StepDraft(
                                "找到主棋盘",
                                TeachingMove.VISUAL,
                                "在图中找到拼接后的主棋盘，再按同样关系放到桌面中央。",
                                List.of(setupLayout.chunkId(), setupPlacement.chunkId()),
                                new VisualFocusDraft(4, "拼接后的主棋盘", 300, 100, 600, 700))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(firstComponent, setupLayout, setupPlacement),
                visionModel,
                new PolicyEvidenceVerifier(),
                (request, risk) -> {
                    reviewedClaimCounts.add(request.claims().size());
                    return new GeneratedContentCritic.Review(false, List.of());
                },
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(reviewedClaimCounts).containsExactly(1);
    }

    @Test
    void rejectsVisualOutputInsteadOfFabricatingAWholePageFocus() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence visualEvidence = new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Assemble the main board and place it in the middle of the table.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_086, 1_511)));
        AtomicInteger compositions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                compositions.incrementAndGet();
                return new SectionDraft(
                        "完成开局",
                        VisualKind.TABLE_LAYOUT,
                        "组装主棋盘并放到桌面中央。",
                        List.of(chunkId),
                        List.of(new StepDraft(
                                "放置主棋盘",
                                TeachingMove.VISUAL,
                                "组装主棋盘并放到桌面中央。",
                                List.of(chunkId),
                                new VisualFocusDraft(4, "整页不是有效局部", 0, 0, 1_000, 1_000))));
            }
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(visualEvidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(compositions).hasValue(6);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void rejectsVisualBlockWhenNoPageImageReachedTheModel() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(evidence(chunkId, versionId));
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "找到开局区域",
                VisualKind.TABLE_LAYOUT,
                "找到版图中央区域。",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft(
                        "看版图中央",
                        TeachingMove.VISUAL,
                        "找到版图中央的放置区域。",
                        List.of(chunkId))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void carriesOnlyTheTwoMostRecentSupportedChapterEndingsIntoComposition() {
        UUID versionId = UUID.randomUUID();
        Map<TeachingSectionType, RuleEvidence> evidence = Map.of(
                TeachingSectionType.OBJECTIVE,
                sectionEvidence(TeachingSectionType.OBJECTIVE, "Collect the most stars to win.", versionId),
                TeachingSectionType.COMPONENTS,
                sectionEvidence(TeachingSectionType.COMPONENTS, "Each player uses one color of pieces.", versionId),
                TeachingSectionType.SETUP,
                sectionEvidence(TeachingSectionType.SETUP, "Place the board in the center.", versionId));
        AtomicInteger compositions = new AtomicInteger();
        TeachingLessonModel model = request -> {
            int call = compositions.getAndIncrement();
            if (call == 0) {
                assertThat(request.priorSections()).isEmpty();
            } else if (call == 1) {
                assertThat(request.priorSections())
                        .extracting(TeachingLessonModel.PriorSectionContext::topicKey)
                        .containsExactly(TeachingSectionType.OBJECTIVE.name());
                assertThat(request.priorSections().getFirst().closingStep()).contains("most stars");
            } else {
                assertThat(request.priorSections())
                        .extracting(TeachingLessonModel.PriorSectionContext::topicKey)
                        .containsExactly(TeachingSectionType.OBJECTIVE.name(), TeachingSectionType.COMPONENTS.name());
                assertThat(request.priorSections().getLast().closingStep()).contains("one color");
            }
            RuleEvidence source = evidence.get(TeachingSectionType.valueOf(request.topicKey()));
            return new TeachingLessonModel.SectionDraft(
                    request.title(),
                    VisualKind.REFERENCE_CARD,
                    "本节规则提示",
                    List.of(source.chunkId()),
                    List.of(new TeachingLessonModel.StepDraft(source.excerpt(), List.of(source.chunkId()))));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence.get(TeachingSectionType.valueOf(request.query()))),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                6);

        var lesson = agent.create(continuityPlan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(compositions).hasValue(3);
    }

    @Test
    void mergesComplementaryEvidenceAndRemovesDuplicateChunksBeforeComposition() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence primary = evidence(UUID.randomUUID(), versionId);
        RuleEvidence supplementary = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "SETUP",
                "Starting pieces",
                "Each player takes one player board and five coins.",
                3,
                3);
        AtomicInteger retrievalCalls = new AtomicInteger();
        AssistantReadTools tools = request -> {
            assertThat(request.limit()).isEqualTo(3);
            return retrievalCalls.getAndIncrement() == 0
                    ? List.of(primary)
                    : List.of(primary, supplementary);
        };
        TeachingLessonModel model = request -> {
            assertThat(request.evidence()).extracting(TeachingLessonModel.EvidenceInput::chunkId)
                    .containsExactly(primary.chunkId(), supplementary.chunkId());
            return new TeachingLessonModel.SectionDraft(
                    "完成开局",
                    VisualKind.TABLE_LAYOUT,
                    "桌面布置示意",
                    List.of(supplementary.chunkId()),
                    List.of(new TeachingLessonModel.StepDraft(
                            "将棋盘放在中央，每位玩家拿取自己的玩家板和五枚硬币。",
                            List.of(primary.chunkId(), supplementary.chunkId()))));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools, model, new PolicyEvidenceVerifier(), acceptedCritic(),
                new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(retrievalCalls).hasValue(3);
        assertThat(lesson.sections().getFirst().visualSourcePages()).containsExactly(3);
        assertThat(lesson.sections().getFirst().visualSourceChunkIds())
                .containsExactly(supplementary.chunkId());
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds())
                .containsExactly(primary.chunkId(), supplementary.chunkId());
    }

    @Test
    void interleavesRankedPrimaryAndSupplementaryEvidenceWithinTheContextCap() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidence> primary = List.of(
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId));
        List<RuleEvidence> supplementary = List.of(
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId),
                evidence(UUID.randomUUID(), versionId));
        AtomicInteger calls = new AtomicInteger();
        AssistantReadTools tools = request -> calls.getAndIncrement() == 0 ? primary : supplementary;
        TeachingLessonModel model = request -> {
            List<UUID> expectedOrder = List.of(
                    primary.get(0).chunkId(),
                    supplementary.get(0).chunkId(),
                    primary.get(1).chunkId(),
                    supplementary.get(1).chunkId(),
                    primary.get(2).chunkId(),
                    supplementary.get(2).chunkId(),
                    primary.get(3).chunkId(),
                    supplementary.get(3).chunkId());
            assertThat(request.evidence())
                    .extracting(TeachingLessonModel.EvidenceInput::chunkId)
                    .containsExactlyElementsOf(expectedOrder);
            return new TeachingLessonModel.SectionDraft(
                    "平衡证据开局",
                    VisualKind.TABLE_LAYOUT,
                    "桌面布置示意",
                    List.of(supplementary.getFirst().chunkId()),
                    List.of(new TeachingLessonModel.StepDraft("按规则完成开局。", expectedOrder)));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(calls).hasValue(3);
    }

    @Test
    void composesFromPrimaryEvidenceWhenSupplementaryIntentFailsOrBudgetIsExhausted() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence primary = evidence(UUID.randomUUID(), versionId);
        AtomicInteger retrievalCalls = new AtomicInteger();
        AssistantReadTools tools = request -> {
            if (retrievalCalls.getAndIncrement() == 0) {
                return List.of(primary);
            }
            throw new IllegalStateException("supplementary search unavailable");
        };
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "完成开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(primary.chunkId()),
                List.of(new TeachingLessonModel.StepDraft("将棋盘放在桌面中央。", List.of(primary.chunkId()))));

        GroundedTeachingAgent withFailedSupplement = new GroundedTeachingAgent(
                tools, model, new PolicyEvidenceVerifier(), acceptedCritic(),
                new ImmediateAuditedAgentInvocations(), 4);
        var afterFailure = withFailedSupplement.create(plan(versionId), UUID.randomUUID());

        AtomicInteger oneCall = new AtomicInteger();
        GroundedTeachingAgent withOneToolCall = new GroundedTeachingAgent(
                request -> {
                    oneCall.incrementAndGet();
                    return List.of(primary);
                },
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                1);
        var atBudgetLimit = withOneToolCall.create(plan(versionId), UUID.randomUUID());

        assertThat(afterFailure.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(retrievalCalls).hasValue(3);
        assertThat(atBudgetLimit.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(oneCall).hasValue(1);
    }

    @Test
    void rejectsModelStepsThatCiteEvidenceOutsideTheRetrievedScope() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        AssistantReadTools retrieval = request -> List.of(evidence);
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(evidence.chunkId()),
                List.of(new TeachingLessonModel.StepDraft("捏造的步骤", List.of(UUID.randomUUID()))));
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        retrieval, model, new PolicyEvidenceVerifier(), acceptedCritic(),
                        new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds()).isEmpty();
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).doesNotContain("捏造");
    }

    @Test
    void rejectsVisualCitationsOutsideTheRetrievedScope() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(UUID.randomUUID()),
                List.of(new TeachingLessonModel.StepDraft(
                        "将棋盘放在桌面中央。", List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(lesson.sections().getFirst().visualSourceChunkIds()).isEmpty();
    }

    @Test
    void rejectsInferredEmojiUsedAsAMissingRulebookIcon() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "开局",
                VisualKind.REFERENCE_CARD,
                "桌面布置",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "领取奖励", TeachingMove.DO, "领取一个🔬。", List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void routesARewrittenRelativeRuleThroughTheDocumentAgnosticCritic() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence gate = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Tidal gate",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the current channel.",
                12,
                12);
        TeachingLessonModel model = request -> new SectionDraft(
                "穿过潮汐门",
                VisualKind.REFERENCE_CARD,
                "升起船帆后可以穿过潮汐门。",
                List.of(gate.chunkId()),
                List.of(new StepDraft(
                        "支付费用",
                        TeachingMove.WATCH,
                        "升起船帆后可以穿过潮汐门，固定支付3枚硬币。",
                        List.of(gate.chunkId()))));
        GeneratedContentCritic critic = (request, risk) -> new GeneratedContentCritic.Review(
                true,
                List.of(new Issue(
                        IssueType.CONTRADICTION,
                        1,
                        List.of(gate.chunkId()),
                        "The relative crossing cost was replaced by an unsupported fixed amount.")));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(gate),
                model,
                new PolicyEvidenceVerifier(),
                critic,
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void doesNotRewriteGameSpecificTextInsideApplicationCode() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence gate = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Tidal gate",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the current channel.",
                12,
                12);
        TeachingLessonModel model = request -> new SectionDraft(
                "穿过潮汐门",
                VisualKind.REFERENCE_CARD,
                "升起船帆后可以穿过潮汐门。",
                List.of(gate.chunkId()),
                List.of(new StepDraft(
                        "沿用航道费用",
                        TeachingMove.WATCH,
                        "费用与进入当前航道相同（本局当前显示为3枚硬币）。",
                        List.of(gate.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(gate),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().steps().getFirst().text())
                .isEqualTo("费用与进入当前航道相同（本局当前显示为3枚硬币）。");
    }

    @Test
    void rejectsInternalEvidenceLanguageFromPlayerFacingSteps() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "开局",
                VisualKind.REFERENCE_CARD,
                "桌面布置",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "领取奖励",
                        TeachingMove.DO,
                        "已提供的证据中没有提到具体奖励。",
                        List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void rejectsPendingRuleLanguageFromPlayerFacingSteps() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "游戏结束",
                VisualKind.REFERENCE_CARD,
                "确认游戏结束条件。",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "游戏何时结束？",
                        TeachingMove.UNDERSTAND,
                        "当前可用的结束触发条件未在已有资料中说明。请等待确定游戏结束的方式后再进行计分。",
                        List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void rejectsAClaimThatTheSuppliedRulebookPageDoesNotContainTheEndingRule() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "结束、计分与胜者",
                VisualKind.REFERENCE_CARD,
                "页面没有提到游戏何时结束或如何计分。",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "当前游戏材料不含结束规则",
                        TeachingMove.UNDERSTAND,
                        "关于结束触发、最终处理与同分规则，需要从游戏的其他规则部分来了解。",
                        List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void rejectsInternalShortEvidenceReferencesFromPlayerFacingSteps() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "开局",
                VisualKind.REFERENCE_CARD,
                "桌面布置",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "摆放棋盘",
                        TeachingMove.DO,
                        "把主棋盘放到桌面中央 E1。",
                        List.of(evidence.chunkId()))));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void sendsAnAmbiguousInlineGlyphClaimToTheGenericCritic() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Harvest",
                "This will always include Details are on page 20. a and some number of points.",
                11,
                11);
        TeachingLessonModel model = request -> new SectionDraft(
                "收获",
                VisualKind.REFERENCE_CARD,
                "获得版图显示的奖励。",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "领取奖励",
                        TeachingMove.DO,
                        "奖励包括一块木材和一些分数。",
                        List.of(evidence.chunkId()))));
        GeneratedContentCritic critic = (request, risk) -> new GeneratedContentCritic.Review(
                true,
                List.of(new Issue(
                        IssueType.UNSUPPORTED_CLAIM,
                        1,
                        List.of(evidence.chunkId()),
                        "The missing glyph does not establish that the reward is wood.")));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                critic,
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void sendsInventedExampleStartingValuesToTheGenericCritic() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence evidence = evidence(UUID.randomUUID(), versionId);
        TeachingLessonModel model = request -> new SectionDraft(
                "移动",
                VisualKind.REFERENCE_CARD,
                "每点移动力移动一格。",
                List.of(evidence.chunkId()),
                List.of(new StepDraft(
                        "移动示例",
                        TeachingMove.EXAMPLE,
                        "假设你手上有3块木材，支付1块木材后还剩2块。",
                        List.of(evidence.chunkId()))));
        GeneratedContentCritic critic = (request, risk) -> new GeneratedContentCritic.Review(
                true,
                List.of(new Issue(
                        IssueType.UNSUPPORTED_CLAIM,
                        1,
                        List.of(evidence.chunkId()),
                        "The example invents a starting inventory of three wood.")));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence),
                model,
                new PolicyEvidenceVerifier(),
                critic,
                new ImmediateAuditedAgentInvocations(),
                4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void rejectsEvidenceFromAnotherDocumentVersionBeforeComposition() {
        UUID versionId = UUID.randomUUID();
        RuleEvidence wrongVersion = evidence(UUID.randomUUID(), UUID.randomUUID());
        AssistantReadTools retrieval = request -> List.of(wrongVersion);
        TeachingLessonModel model = request -> {
            throw new AssertionError("model must not receive version-conflicting evidence");
        };
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        retrieval, model, new PolicyEvidenceVerifier(), acceptedCritic(),
                        new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void rejectsConflictingSnapshotsOfTheSameChunkAcrossRetrievalIntents() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence first = evidence(chunkId, versionId);
        RuleEvidence conflicting = new RuleEvidence(
                chunkId, versionId, "SETUP", "Different source identity", "Place the board somewhere else.", 2, 3);
        AtomicInteger calls = new AtomicInteger();
        AssistantReadTools retrieval = request -> calls.getAndIncrement() == 0
                ? List.of(first)
                : List.of(conflicting);
        TeachingLessonModel model = request -> {
            throw new AssertionError("model must not receive conflicting evidence snapshots");
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                retrieval, model, new PolicyEvidenceVerifier(), acceptedCritic(),
                new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void retainsACitedSectionAfterOneBoundedPostPublicationCorrection() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AssistantReadTools retrieval = request -> List.of(evidence(chunkId, versionId));
        AtomicInteger modelCalls = new AtomicInteger();
        TeachingLessonModel model = request -> {
            modelCalls.incrementAndGet();
            return new TeachingLessonModel.SectionDraft(
                "开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft("玩家可以任意放置棋盘。", List.of(chunkId))));
        };
        AtomicInteger criticCalls = new AtomicInteger();
        RecordingInvocations invocations = new RecordingInvocations();
        GeneratedContentCritic rejectingCritic = (request, risk) -> {
            criticCalls.incrementAndGet();
            return new GeneratedContentCritic.Review(
                    true,
                    List.of(new Issue(
                            IssueType.CONTRADICTION, 1, List.of(chunkId), "The placement contradicts the evidence.")));
        };
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        retrieval, model, new PolicyEvidenceVerifier(), rejectingCritic,
                        invocations, 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).contains("任意放置");
        assertThat(modelCalls).hasValue(2);
        assertThat(criticCalls).hasValue(1);
        assertThat(invocations.diagnostics).containsExactly(
                new Diagnostic("validateTeachingSection|1|0", ActivityOutcome.SUCCEEDED,
                        "Teaching draft accepted: CITED_DRAFT_ACCEPTED"),
                new Diagnostic("publishTeachingSection|1", ActivityOutcome.SUCCEEDED,
                        "Teaching section published: CITED_DRAFT_PUBLISHED"),
                new Diagnostic("validateTeachingSection|1|0", ActivityOutcome.REJECTED,
                        "Teaching draft rejected: CRITIC_CONTRADICTION@1"),
                new Diagnostic("validateTeachingSection|1|1", ActivityOutcome.SUCCEEDED,
                        "Teaching draft accepted: POST_PUBLICATION_CORRECTION_APPLIED"),
                new Diagnostic("publishTeachingSection|1", ActivityOutcome.SUCCEEDED,
                        "Teaching section published: POST_PUBLICATION_REVIEW_PENDING"));
    }

    @Test
    void boundsWholeLessonCorrectionsWhileKeepingAllCitedChaptersReadable() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                25,
                "Game",
                "Premise",
                List.of(
                        topic(1, TeachingSectionType.SETUP),
                        topic(2, TeachingSectionType.ACTIONS),
                        topic(3, TeachingSectionType.SCORING)),
                "player",
                Instant.now());
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger criticCalls = new AtomicInteger();
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingLessonModel model = request -> {
            modelCalls.incrementAndGet();
            return new SectionDraft(
                    "可照着完成的一节",
                    VisualKind.REFERENCE_CARD,
                    "把主棋盘放在桌面中央。",
                    List.of(chunkId),
                    List.of(new StepDraft(
                            "放置主棋盘", TeachingMove.DO, "把主棋盘放在桌面中央。", List.of(chunkId))));
        };
        GeneratedContentCritic critic = (request, risk) -> {
            criticCalls.incrementAndGet();
            return new GeneratedContentCritic.Review(
                    true,
                    List.of(
                            new Issue(IssueType.CONTRADICTION, 1, List.of(chunkId), "first"),
                            new Issue(IssueType.CONTRADICTION, 3, List.of(chunkId), "second"),
                            new Issue(IssueType.CONTRADICTION, 5, List.of(chunkId), "third")));
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                model,
                new PolicyEvidenceVerifier(),
                critic,
                invocations,
                12);

        IllustratedLesson lesson = agent.create(plan, UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections()).allMatch(section -> section.evidenceStatus() == EvidenceStatus.CITED_DRAFT);
        assertThat(modelCalls).hasValue(5);
        assertThat(criticCalls).hasValue(1);
        assertThat(invocations.diagnostics).contains(new Diagnostic(
                "publishTeachingSection|3",
                ActivityOutcome.SUCCEEDED,
                "Teaching section published: POST_PUBLICATION_REVIEW_DEFERRED_FOR_INCREMENTAL_REVIEW"));
    }

    @Test
    void reviewsEveryChapterForObjectiveCoverageInOneBoundedLessonPass() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                25,
                "Game",
                "Premise",
                List.of(
                        topic(1, TeachingSectionType.COMPONENTS),
                        topic(2, TeachingSectionType.ACTIONS),
                        topic(3, TeachingSectionType.SETUP),
                        topic(4, TeachingSectionType.END_CONDITIONS),
                        topic(5, TeachingSectionType.SCORING),
                        topic(6, TeachingSectionType.OBJECTIVE)),
                "player",
                Instant.now());
        List<String> reviewedObjectives = new java.util.ArrayList<>();
        GeneratedContentCritic recordingCritic = (request, risk) -> {
            reviewedObjectives.add(request.taskContext().objective());
            return new GeneratedContentCritic.Review(true, List.of());
        };
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                request -> List.of(evidence(chunkId, versionId)),
                new FakeTeachingLessonModel(),
                new PolicyEvidenceVerifier(),
                recordingCritic,
                new ImmediateAuditedAgentInvocations(),
                24);

        var lesson = agent.create(plan, UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections())
                .filteredOn(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .hasSize(6);
        assertThat(reviewedObjectives).hasSize(1);
        assertThat(String.join("\n", reviewedObjectives)).contains(
                "第1章「COMPONENTS」：Explain COMPONENTS",
                "第2章「ACTIONS」：Explain ACTIONS",
                "第3章「SETUP」：Explain SETUP",
                "第4章「END_CONDITIONS」：Explain END_CONDITIONS",
                "第5章「SCORING」：Explain SCORING",
                "第6章「OBJECTIVE」：Explain OBJECTIVE");
    }

    private record Diagnostic(String operation, ActivityOutcome outcome, String summary) {}

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final java.util.ArrayList<Diagnostic> diagnostics = new java.util.ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            return invocation.get();
        }

        @Override
        public void record(
                UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {
            assertThat(type).isEqualTo(ActivityType.VALIDATION);
            diagnostics.add(new Diagnostic(operation, outcome, summary));
        }
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
                List.of(topic(1, TeachingSectionType.SETUP)),
                "player",
                Instant.now());
    }

    private SectionDraft oneStepDraft(UUID chunkId, String text) {
        return new SectionDraft(
                "可执行步骤",
                VisualKind.REFERENCE_CARD,
                "按引用完成这一节。",
                List.of(chunkId),
                List.of(new StepDraft("照着做", TeachingMove.DO, text, List.of(chunkId))));
    }

    private SectionDraft setupInventoryDraft(UUID chunkId, String finalCheck) {
        return new SectionDraft(
                "摆好中央展示区",
                VisualKind.TABLE_LAYOUT,
                "按顺序摆好组件。",
                List.of(chunkId),
                List.of(
                        new StepDraft(
                                "抽取标记",
                                TeachingMove.DO,
                                "从布袋中随机抽取4个野生动物标记，摆到中央。",
                                List.of(chunkId)),
                        new StepDraft("检查供应", TeachingMove.CHECK, finalCheck, List.of(chunkId))));
    }

    private TeachingPlan alternativeRoutePlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                4,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        "boat-routes",
                        "Boat routes",
                        "Learn how to move a boat along a river or tunnel route.",
                        true,
                        false,
                        List.of("river route", "tunnel route"),
                        List.of("core_loop"))),
                "player",
                Instant.now());
    }

    private TeachingPlan continuityPlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                10,
                "Game",
                "Premise",
                List.of(
                        topic(1, TeachingSectionType.OBJECTIVE),
                        topic(2, TeachingSectionType.COMPONENTS),
                        topic(3, TeachingSectionType.SETUP)),
                "player",
                Instant.now());
    }

    private TeachingPlan nonVisualPlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                4,
                2,
                20,
                "Game",
                "Premise",
                List.of(new PlannedSection(
                        1,
                        TeachingSectionType.ROUND_STRUCTURE.name(),
                        "一轮如何结束",
                        "Explain round progression",
                        true,
                        false,
                        List.of("round end", "pass"),
                        List.of("core_loop"))),
                "player",
                Instant.now());
    }

    private PlannedSection topic(int position, TeachingSectionType type) {
        String tag = switch (type) {
            case SETUP -> "setup";
            case ACTIONS, ROUND_STRUCTURE, PHASES -> "core_loop";
            case END_CONDITIONS -> "end";
            case SCORING -> "scoring";
            default -> type.name().toLowerCase();
        };
        return new PlannedSection(
                position,
                type.name(),
                type.name(),
                "Explain " + type.name(),
                true,
                type == TeachingSectionType.SETUP || type == TeachingSectionType.COMPONENTS,
                List.of(type.name(), "More " + type.name()),
                List.of(tag));
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }

    private RuleEvidence evidence(UUID chunkId, UUID versionId) {
        return new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Setup",
                "Place the board in the center of the table.",
                2,
                3);
    }

    private RuleEvidence sectionEvidence(TeachingSectionType type, String excerpt, UUID versionId) {
        return new RuleEvidence(
                UUID.randomUUID(), versionId, type.name(), type.name(), excerpt, type.ordinal() + 1, type.ordinal() + 1);
    }
}
