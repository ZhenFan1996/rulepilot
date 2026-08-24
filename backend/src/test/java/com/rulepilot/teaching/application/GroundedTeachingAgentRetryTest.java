package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

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
        GeneratedContentCritic critic = (request, risk) -> new GeneratedContentCritic.Review(true, List.of());
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                critic,
                invocations,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                1,
                3);
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
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                (request, risk) -> new GeneratedContentCritic.Review(true, List.of()),
                invocations,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                1,
                3);
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
