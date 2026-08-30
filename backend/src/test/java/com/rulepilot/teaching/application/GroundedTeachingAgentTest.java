package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.ProviderFailureException;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.WholeGameContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class GroundedTeachingAgentTest {

    @Test
    void oneChapterProviderAndVisualFailuresDoNotEraseAnIndependentReadableChapter() {
        UUID versionId = UUID.randomUUID();
        AssistantReadTools tools = request -> List.of(evidence(versionId, request.query()));
        TeachingLessonModel model = request -> {
            if (request.topicKey().equals("unavailable")) {
                throw new ProviderFailureException(new IllegalStateException("provider unavailable"));
            }
            UUID evidenceId = request.evidence().getFirst().chunkId();
            return new SectionDraft(
                    "A readable chapter",
                    List.of(new StepDraft(
                            "Take the supported action",
                            TeachingMove.DO,
                            "Use the option described by the cited rule.",
                            List.of(evidenceId))));
        };
        RecordingInvocations invocations = new RecordingInvocations();
        VisualRulebookPageFacts visualFacts = VisualRulebookPageFacts.empty();
        VisualLessonEnricher visuals = mock(VisualLessonEnricher.class);
        when(visuals.supportsVisualEvidence("player")).thenReturn(true);
        when(visuals.enrichSection(
                        eq(versionId), any(LessonSection.class), any(), eq("player"), any(), any()))
                .thenThrow(new IllegalStateException("image provider unavailable"));
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                invocations,
                visualFacts,
                VisualRulebookCatalogerTestFixture.unavailable(tools, invocations, visualFacts),
                visuals);
        List<IllustratedLesson> snapshots = new ArrayList<>();

        IllustratedLesson lesson = agent.createBase(plan(versionId), UUID.randomUUID(), null, snapshots::add);

        assertThat(lesson.status()).isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(lesson.sections()).singleElement().satisfies(section -> {
            assertThat(section.topicKey()).isEqualTo("readable");
            assertThat(section.steps().getFirst().text())
                    .isEqualTo("Use the option described by the cited rule.");
            assertThat(section.steps().getFirst().visualFoci()).isEmpty();
        });
        assertThat(snapshots).singleElement().satisfies(snapshot ->
                assertThat(snapshot.sections()).extracting(LessonSection::topicKey).containsExactly("readable"));
        assertThat(invocations.records)
                .anySatisfy(record -> {
                    assertThat(record.operation()).isEqualTo("publishTeachingSection|1");
                    assertThat(record.outcome()).isEqualTo(ActivityOutcome.REJECTED);
                    assertThat(record.summary()).contains("BASE_DRAFT_WITHHELD");
                })
                .anySatisfy(record -> {
                    assertThat(record.operation()).isEqualTo("publishTeachingSection|2");
                    assertThat(record.outcome()).isEqualTo(ActivityOutcome.SUCCEEDED);
                });
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                null,
                "Independent chapters",
                "Teach every chapter that can be grounded.",
                new WholeGameContext(
                        List.of(),
                        List.of("The unavailable chapter remains unresolved.")),
                List.of(section(1, "unavailable"), section(2, "readable")),
                "player",
                Instant.EPOCH);
    }

    private TeachingPlan.PlannedSection section(int position, String topicKey) {
        return new TeachingPlan.PlannedSection(
                position,
                topicKey,
                "Chapter " + position,
                "Explain " + topicKey + ".",
                true,
                false,
                List.of(topicKey),
                List.of(),
                List.of());
    }

    private RuleEvidence evidence(UUID versionId, String query) {
        UUID id = UUID.nameUUIDFromBytes(query.getBytes(StandardCharsets.UTF_8));
        return new RuleEvidence(
                id,
                versionId,
                "RULE",
                query,
                "The source describes the supported option for " + query + ".",
                1,
                1);
    }

    private static final class RecordingInvocations implements AuditedAgentInvocations {
        private final List<Record> records = new CopyOnWriteArrayList<>();

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
                UUID runId,
                ActivityType type,
                String operation,
                ActivityOutcome outcome,
                String summary) {
            records.add(new Record(operation, outcome, summary));
        }
    }

    private record Record(String operation, ActivityOutcome outcome, String summary) {}
}
