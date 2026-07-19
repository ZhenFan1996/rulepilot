package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GroundedTeachingAgentTest {

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
        GeneratedContentCritic critic = (request, risk) -> {
            criticCalls.incrementAndGet();
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            assertThat(request.taskContext().objective()).contains("SETUP");
            assertThat(request.taskContext().requiredCoverage()).contains("setup");
            assertThat(request.claims()).hasSize(2);
            assertThat(request.claims().getFirst().text()).isEqualTo("桌面布置示意");
            assertThat(request.claims().getFirst().citationIds()).containsExactly(chunkId);
            assertThat(request.claims().get(1).text()).isEqualTo("摆放主棋盘：将棋盘放在桌面中央。");
            return new GeneratedContentCritic.Review(true, List.of());
        };
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        tools, model, new PolicyEvidenceVerifier(), critic,
                        new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.COMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(lesson.sections().getFirst().visualSourcePages()).containsExactly(2, 3);
        assertThat(lesson.sections().getFirst().visualSourceChunkIds()).containsExactly(chunkId);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourcePages()).containsExactly(2, 3);
        assertThat(lesson.sections().getFirst().steps().getFirst().sourceChunkIds()).containsExactly(chunkId);
        assertThat(lesson.sections().getFirst().steps().getFirst().heading()).isEqualTo("摆放主棋盘");
        assertThat(lesson.sections().getFirst().steps().getFirst().kind()).isEqualTo(TeachingMove.DO);
        assertThat(retrievalCalls).hasValue(2);
        assertThat(criticCalls).hasValue(1);
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
        AssistantReadTools tools = request -> retrievalCalls.getAndIncrement() == 0
                ? List.of(primary)
                : List.of(primary, supplementary);
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
        assertThat(retrievalCalls).hasValue(2);
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
        assertThat(calls).hasValue(2);
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
        assertThat(retrievalCalls).hasValue(2);
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
    void degradesSectionRejectedByEvaluationCritic() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AssistantReadTools retrieval = request -> List.of(evidence(chunkId, versionId));
        TeachingLessonModel model = request -> new TeachingLessonModel.SectionDraft(
                "开局",
                VisualKind.TABLE_LAYOUT,
                "桌面布置示意",
                List.of(chunkId),
                List.of(new TeachingLessonModel.StepDraft("玩家可以任意放置棋盘。", List.of(chunkId))));
        GeneratedContentCritic rejectingCritic = (request, risk) -> new GeneratedContentCritic.Review(
                true,
                List.of(new Issue(
                        IssueType.CONTRADICTION, 1, List.of(chunkId), "The placement contradicts the evidence.")));
        GroundedTeachingAgent agent =
                new GroundedTeachingAgent(
                        retrieval, model, new PolicyEvidenceVerifier(), rejectingCritic,
                        new ImmediateAuditedAgentInvocations(), 4);

        var lesson = agent.create(plan(versionId), UUID.randomUUID());

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus())
                .isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(lesson.sections().getFirst().steps().getFirst().text()).doesNotContain("任意放置");
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
