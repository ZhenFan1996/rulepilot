package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.CandidateRejection;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class TeachingSectionDraftComposerTest {

    @Test
    void returnsCompleteInvalidJsonAndTypedBoundaryToTheSameAgentForFullReplacement() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String rejectedJson = "{\"title\":\"Repair\",\"steps\":\"not-an-array\",\"providerField\":true}";
        AtomicReference<CandidateRejection> observed = new AtomicReference<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                throw new InvalidOutputException(
                        "SCHEMA_MISMATCH",
                        "$.steps",
                        "steps must be an array",
                        rejectedJson,
                        null);
            }

            @Override
            public SectionDraft continueAfterRejection(SectionRequest request, CandidateRejection rejection) {
                observed.set(rejection);
                return new SectionDraft(
                        "修复系统",
                        List.of(new StepDraft(
                                "选择系统",
                                TeachingMove.DO,
                                "花费一次行动修复受损系统。",
                                List.of(evidenceId))));
            }
        };
        TeachingPlan.PlannedSection planned = new TeachingPlan.PlannedSection(
                1, "repair", "Repair", "Explain repair.", true, false,
                List.of("repair"), List.of(), List.of(8));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(), versionId, "Game", "Learn.", List.of(planned), "owner", Instant.EPOCH);
        RuleEvidence evidence = new RuleEvidence(
                evidenceId, versionId, "RULE", "Repair", "Spend one action to repair a system.", 8, 8);
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model,
                new PolicyEvidenceVerifier(),
                directInvocations(),
                VisualRulebookPageFacts.empty());

        TeachingSectionDraftCandidate result = composer.compose(
                plan, planned, List.of(), List.of(evidence), UUID.randomUUID(), 0);

        assertThat(result.section().steps().getFirst().text()).isEqualTo("花费一次行动修复受损系统。");
        assertThat(observed.get()).satisfies(rejection -> {
            assertThat(rejection.candidateJson()).isEqualTo(rejectedJson);
            assertThat(rejection.code()).isEqualTo("SCHEMA_MISMATCH");
            assertThat(rejection.path()).isEqualTo("$.steps");
            assertThat(rejection.reason()).contains("steps must be an array");
            assertThat(rejection.schema()).isNotBlank();
            assertThat(rejection.allowedEvidenceIdentities()).contains(evidenceId.toString());
        });
    }

    private AuditedAgentInvocations directInvocations() {
        return new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    com.rulepilot.assistant.AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    Supplier<T> invocation,
                    ToIntFunction<T> outputTokenEstimator) {
                return invocation.get();
            }
        };
    }
}
