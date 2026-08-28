package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GroundedTeachingAgentRetryTest {

    @Test
    void repairsMalformedOutputInsideTheComposerWithoutRepeatingEvidenceRetrieval() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AtomicInteger searches = new AtomicInteger();
        AssistantReadTools tools = request -> {
            searches.incrementAndGet();
            return List.of(new RuleEvidence(
                    evidenceId,
                    versionId,
                    "SETUP",
                    "Central board",
                    "Place the central board in the middle before the first turn.",
                    2,
                    2));
        };
        AtomicInteger modelAttempts = new AtomicInteger();
        TeachingLessonModel model = request -> {
            if (modelAttempts.incrementAndGet() == 1) {
                throw new InvalidOutputException("malformed structured output", null);
            }
            return validDraft(evidenceId);
        };
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Retry fixture",
                "Teach one grounded setup relation.",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "setup",
                        "开局准备",
                        "Explain how to place the central board before the first turn.",
                        true,
                        false,
                        List.of("central board setup"),
                        List.of("setup"))),
                "player",
                Instant.now());

        var lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections()).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED));
        assertThat(searches).hasValue(1);
        assertThat(invocations.toolCalls).hasValue(1);
        assertThat(modelAttempts).hasValue(2);
        assertThat(invocations.modelCalls).hasValue(2);
    }

    @Test
    void stopsAfterTheOwnedStructuredRepairWhenNoNewObservationExists() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AtomicInteger searches = new AtomicInteger();
        AssistantReadTools tools = request -> {
            searches.incrementAndGet();
            return List.of(new RuleEvidence(
                    evidenceId,
                    versionId,
                    "SETUP",
                    "Central board",
                    "Place the central board in the middle before the first turn.",
                    2,
                    2));
        };
        AtomicInteger modelAttempts = new AtomicInteger();
        TeachingLessonModel model = request -> {
            modelAttempts.incrementAndGet();
            throw new InvalidOutputException("malformed structured output", null);
        };
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Retry ceiling fixture",
                "Withhold a section whose provider output never satisfies the typed contract.",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "setup",
                        "开局准备",
                        "Explain how to place the central board before the first turn.",
                        true,
                        false,
                        List.of("central board setup"),
                        List.of("setup"))),
                "player",
                Instant.now());

        var lesson = agent.createBase(plan, UUID.randomUUID(), null, ignored -> {});

        assertThat(lesson.sections()).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
        assertThat(searches).hasValue(1);
        assertThat(invocations.toolCalls).hasValue(1);
        assertThat(modelAttempts).hasValue(2);
        assertThat(invocations.modelCalls).hasValue(2);
    }

    @Test
    void publishesTheValidatedChapterAndItsSelectedImageInTheSameSnapshot() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(new RuleEvidence(
                evidenceId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle before the first turn.",
                2,
                2));
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        VisualLessonEnricher visuals = mock(VisualLessonEnricher.class);
        when(visuals.supportsVisualEvidence("player")).thenReturn(true);
        VisualFocus focus = new VisualFocus(
                2, "中央主棋盘", "截图显示桌面中央的主棋盘。", 100, 120, 500, 420,
                VisualSourceKind.PAGE_REGION);
        when(visuals.enrichSection(
                        eq(versionId), any(LessonSection.class), any(), eq("player"), any(), any()))
                .thenAnswer(invocation -> {
                    LessonSection section = invocation.getArgument(1);
                    var step = section.steps().getFirst().withVisualFoci(TeachingMove.VISUAL, List.of(focus));
                    LessonSection enriched = new LessonSection(
                            section.position(),
                            section.topicKey(),
                            section.coverageTags(),
                            section.title(),
                            section.required(),
                            section.evidenceStatus(),
                            section.visualKind(),
                            section.visualCaption(),
                            List.of(2),
                            List.of(evidenceId),
                            List.of(step));
                    return new VisualLessonEnricher.SectionEnrichment(
                            enriched,
                            new VisualLessonEnricher.SectionOutcome(
                                    section.position(), VisualLessonEnricher.Outcome.ADDED, "已加入可核对图片"));
                });
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                request -> validDraft(evidenceId),
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts),
                visuals);
        TeachingPlan plan = plan(versionId);
        List<com.rulepilot.teaching.domain.IllustratedLesson> snapshots = new ArrayList<>();

        var lesson = agent.createBase(plan, UUID.randomUUID(), null, snapshots::add);

        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.getFirst().sections().getFirst().steps().getFirst().visualFoci())
                .containsExactly(focus);
        assertThat(lesson.sections().getFirst().steps().getFirst().visualFoci()).containsExactly(focus);
    }

    @Test
    void publishesValidatedTextWhenTheSectionVisualAttemptFails() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(new RuleEvidence(
                evidenceId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle before the first turn.",
                2,
                2));
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        VisualLessonEnricher visuals = mock(VisualLessonEnricher.class);
        when(visuals.supportsVisualEvidence("player")).thenReturn(true);
        when(visuals.enrichSection(
                        eq(versionId), any(LessonSection.class), any(), eq("player"), any(), any()))
                .thenThrow(new IllegalStateException("visual provider unavailable"));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                request -> validDraft(evidenceId),
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts),
                visuals);
        TeachingPlan plan = plan(versionId);
        List<com.rulepilot.teaching.domain.IllustratedLesson> snapshots = new ArrayList<>();

        var lesson = agent.createBase(plan, UUID.randomUUID(), null, snapshots::add);

        assertThat(snapshots).isNotEmpty();
        assertThat(lesson.sections()).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps().getFirst().text()).isEqualTo("把主棋盘放在桌面中央。");
            assertThat(section.steps().getFirst().visualFoci()).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void propagatesAWholeRunStopFromTheFinalSectionVisualAttempt(StopReason stopReason) {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(new RuleEvidence(
                evidenceId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle before the first turn.",
                2,
                2));
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        VisualLessonEnricher visuals = mock(VisualLessonEnricher.class);
        when(visuals.supportsVisualEvidence("player")).thenReturn(true);
        when(visuals.enrichSection(
                        eq(versionId), any(LessonSection.class), any(), eq("player"), any(), any()))
                .thenThrow(new AgentExecutionStoppedException(stopReason));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                request -> validDraft(evidenceId),
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts),
                visuals);
        List<com.rulepilot.teaching.domain.IllustratedLesson> snapshots = new ArrayList<>();

        assertThatThrownBy(() -> agent.createBase(plan(versionId), UUID.randomUUID(), null, snapshots::add))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue("reason", stopReason);

        assertThat(snapshots).singleElement().satisfies(snapshot ->
                assertThat(snapshot.sections()).singleElement().satisfies(section -> {
                    assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
                    assertThat(section.steps().getFirst().text()).isEqualTo("把主棋盘放在桌面中央。");
                    assertThat(section.steps().getFirst().visualFoci()).isEmpty();
                }));
    }

    @Test
    void checkpointsOneAdditionalChapterPerContinuationWorkUnit() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(new RuleEvidence(
                evidenceId,
                versionId,
                "RULE",
                "Verified rule",
                "This is the admitted source for the next chapter.",
                2,
                2));
        CountingInvocations invocations = new CountingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                request -> validDraft(evidenceId),
                new PolicyEvidenceVerifier(),
                cleanCritic(),
                invocations,
                visualFacts,
                3,
                null,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Long lesson fixture",
                "Teach each admitted chapter independently.",
                List.of(
                        plannedSection(1, "setup"),
                        plannedSection(2, "turn"),
                        plannedSection(3, "end")),
                "player",
                Instant.now());
        List<com.rulepilot.teaching.domain.IllustratedLesson> snapshots = new ArrayList<>();

        var continuation = agent.startBase(plan, UUID.randomUUID(), null, snapshots::add);
        var secondChapter = agent.continueBaseWorkUnit(continuation, snapshots::add);
        var thirdChapter = agent.continueBaseWorkUnit(continuation, snapshots::add);

        assertThat(secondChapter.complete()).isFalse();
        assertThat(secondChapter.lesson().sections()).hasSize(2);
        assertThat(thirdChapter.complete()).isTrue();
        assertThat(thirdChapter.lesson().sections()).hasSize(3);
        assertThat(snapshots).extracting(snapshot -> snapshot.sections().size())
                .containsExactly(1, 2, 3);
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Visual lifecycle fixture",
                "Teach one grounded setup relation.",
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

    private TeachingPlan.PlannedSection plannedSection(int position, String topicKey) {
        return new TeachingPlan.PlannedSection(
                position,
                topicKey,
                topicKey,
                "Explain the admitted rule for " + topicKey + ".",
                true,
                false,
                List.of(topicKey + " rule"),
                List.of(topicKey));
    }

    private static SectionDraft validDraft(UUID evidenceId) {
        return new SectionDraft(
                "摆好中央区域",
                VisualKind.REFERENCE_CARD,
                "先摆好主棋盘。",
                List.of(evidenceId),
                List.of(new StepDraft(
                        "放置主棋盘",
                        TeachingMove.DO,
                        "把主棋盘放在桌面中央。",
                        List.of(evidenceId))));
    }

    private static GeneratedContentCritic cleanCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(true, List.of());
    }

    private static final class CountingInvocations implements AuditedAgentInvocations {
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger modelCalls = new AtomicInteger();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.TOOL) toolCalls.incrementAndGet();
            if (type == ActivityType.MODEL) modelCalls.incrementAndGet();
            return invocation.get();
        }
    }
}
