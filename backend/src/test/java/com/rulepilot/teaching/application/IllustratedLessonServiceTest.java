package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.teaching.TeachingLessonModel.ProviderFailureException;
import com.rulepilot.teaching.application.GroundedTeachingAgent.BaseLessonContinuation;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationContinuation;
import com.rulepilot.teaching.application.IllustratedLessonService.GenerationOutcome;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IllustratedLessonServiceTest {

    @Test
    void isolatesCandidateRunDiscoveryFromThePlayerVisiblePlanSubject() {
        UUID planId = UUID.randomUUID();

        UUID first = IllustratedLessonService.candidateSubjectId(planId);

        assertThat(first).isEqualTo(IllustratedLessonService.candidateSubjectId(planId));
        assertThat(first).isNotEqualTo(planId);
        assertThat(first).isNotEqualTo(IllustratedLessonService.candidateSubjectId(UUID.randomUUID()));
    }

    @Test
    void marksAReadablePartialLessonDegradedWithoutReopeningItsExecutionBudget() {
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot verified = run(AssistantRunState.VERIFYING_EVIDENCE, 5);
        RunSnapshot composed = run(verified.id(), AssistantRunState.LESSON_COMPOSITION, 6);
        RunSnapshot degraded = run(verified.id(), AssistantRunState.DEGRADED, 7);
        when(runs.advanceAfterWork(
                        verified.id(), 5, AssistantRunState.LESSON_COMPOSITION,
                        "Cited illustrated lesson is composed"))
                .thenReturn(composed);
        when(runs.advanceAfterWork(
                        verified.id(), 6, AssistantRunState.DEGRADED,
                        "Readable cited lesson retained with incomplete optional or source coverage"))
                .thenReturn(degraded);
        IllustratedLessonService service = service(runs);

        service.finish(new GenerationOutcome(verified, LessonStatus.DRAFT_READY));

        verify(runs).advanceAfterWork(
                verified.id(), 5, AssistantRunState.LESSON_COMPOSITION, "Cited illustrated lesson is composed");
        verify(runs).advanceAfterWork(
                verified.id(),
                6,
                AssistantRunState.DEGRADED,
                "Readable cited lesson retained with incomplete optional or source coverage");
        verify(runs, never()).advance(any(), any(Long.class), any(), any());
    }

    @Test
    void startsFromAnAlreadyHydratedOwnedPlanWithoutReadingThePlanRepositoryAgain() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(plan.documentVersionId()).thenReturn(versionId);
        when(plan.createdBy()).thenReturn("alice");
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        AssistantRuns runs = mock(AssistantRuns.class);
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        WorkloadDemand workload = new WorkloadDemand(95, 77);
        when(agent.workload(plan)).thenReturn(workload);
        DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
        when(documents.findVersion(versionId))
                .thenReturn(java.util.Optional.of(new VersionScope(versionId, UUID.randomUUID(), "READY", "alice")));
        RunSnapshot received = run(planId, "alice", AssistantRunState.RECEIVED, 1);
        when(runs.start(
                        AssistantRunMode.TEACHING,
                        planId,
                        "alice",
                        workload))
                .thenReturn(received);
        IllustratedLessonService service = new IllustratedLessonService(
                plans,
                agent,
                mock(IllustratedLessonRepository.class),
                runs,
                documents,
                ObservationRegistry.NOOP,
                mock(IllustratedLessonProgressPublisher.class));

        RunSnapshot started = service.begin(plan, "alice");

        assertThat(started).isEqualTo(received);
        verify(documents).findVersion(versionId);
        verify(plans, never()).findById(planId);
        verify(runs).start(
                AssistantRunMode.TEACHING,
                planId,
                "alice",
                workload);
    }

    @Test
    void rejectsAnAlreadyHydratedPlanWhenThePreparedRunHasAnotherSubject() {
        UUID planId = UUID.randomUUID();
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(plan.createdBy()).thenReturn("alice");
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        IllustratedLessonService service = new IllustratedLessonService(
                mock(TeachingPlanRepository.class),
                agent,
                mock(IllustratedLessonRepository.class),
                mock(AssistantRuns.class),
                mock(DocumentVersionScopeLookup.class),
                ObservationRegistry.NOOP,
                mock(IllustratedLessonProgressPublisher.class));
        RunSnapshot wrongRun = run(UUID.randomUUID(), "alice", AssistantRunState.RECEIVED, 1);

        assertThatThrownBy(() -> service.startGeneration(plan, "alice", wrongRun))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prepared teaching run does not match the plan");

        verifyNoInteractions(agent);
    }

    @Test
    void marksTheRunCancelledWhenTheFinalOptionalWorkPropagatesAStop() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(planId);
        when(plan.documentVersionId()).thenReturn(versionId);
        when(plan.createdBy()).thenReturn("alice");
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot received = run(planId, "alice", AssistantRunState.RECEIVED, 1);
        RunSnapshot documentReady = run(received.id(), planId, "alice", AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planned = run(received.id(), planId, "alice", AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot retrievalPlanned = run(received.id(), planId, "alice", AssistantRunState.RETRIEVAL_PLANNING, 4);
        RunSnapshot retrieving = run(received.id(), planId, "alice", AssistantRunState.RETRIEVING, 5);
        when(runs.advance(received.id(), 1, AssistantRunState.DOCUMENT_READINESS,
                        "Rule document readiness is checked"))
                .thenReturn(documentReady);
        when(runs.advance(received.id(), 2, AssistantRunState.LESSON_PLANNING,
                        "Teaching plan is loaded"))
                .thenReturn(planned);
        when(runs.advance(received.id(), 3, AssistantRunState.RETRIEVAL_PLANNING,
                        "Required lesson evidence is planned"))
                .thenReturn(retrievalPlanned);
        when(runs.advance(received.id(), 4, AssistantRunState.RETRIEVING,
                        "Allow-listed rule search is running"))
                .thenReturn(retrieving);
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        when(agent.startBase(any(), any(), any(), any()))
                .thenThrow(new AgentExecutionStoppedException(StopReason.CANCELLED));
        IllustratedLessonService service = new IllustratedLessonService(
                mock(TeachingPlanRepository.class),
                agent,
                mock(IllustratedLessonRepository.class),
                runs,
                mock(DocumentVersionScopeLookup.class),
                ObservationRegistry.NOOP,
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.startGeneration(plan, "alice", received))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue("reason", StopReason.CANCELLED);

        verify(runs).fail(
                received.id(),
                5,
                "AGENT_CANCELLED",
                "Teaching workflow stopped by execution budget");
        verify(runs, never()).advanceAfterWork(any(), any(Long.class), any(), any());
    }

    @Test
    void classifiesMissingOrInvalidPlanInputAsPermanentWithoutPersistingPrivateDetails() {
        UUID planId = UUID.randomUUID();
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot received = run(planId, "alice", AssistantRunState.RECEIVED, 1);
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        when(plans.findById(planId)).thenReturn(Optional.empty());
        IllustratedLessonService service = service(
                plans,
                mock(GroundedTeachingAgent.class),
                mock(IllustratedLessonRepository.class),
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.startGeneration(planId, "alice", received))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("teaching plan does not exist");

        verify(runs).fail(
                received.id(),
                1,
                "TEACHING_PLAN_INVALID",
                "Teaching input is no longer valid");
    }

    @Test
    void classifiesPlanRepositoryOutageAsRetryablePlanRetrieval() {
        UUID planId = UUID.randomUUID();
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot received = run(planId, "alice", AssistantRunState.RECEIVED, 1);
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        when(plans.findById(planId)).thenThrow(new IllegalStateException("private database endpoint"));
        IllustratedLessonService service = service(
                plans,
                mock(GroundedTeachingAgent.class),
                mock(IllustratedLessonRepository.class),
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.startGeneration(planId, "alice", received))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("private database endpoint");

        verify(runs).fail(
                received.id(),
                1,
                "TEACHING_PLAN_RETRIEVAL_FAILED",
                "Teaching plan and source scope could not be loaded");
    }

    @Test
    void classifiesFirstSectionDependencyFailureAsEvidenceRetrieval() {
        TeachingPlan plan = ownedPlan("alice");
        AssistantRuns runs = mock(AssistantRuns.class);
        GenerationRuns generation = generationRuns(runs, plan.id(), "alice");
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        when(agent.startBase(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("private evidence adapter detail"));
        IllustratedLessonService service = service(
                mock(TeachingPlanRepository.class),
                agent,
                mock(IllustratedLessonRepository.class),
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.startGeneration(plan, "alice", generation.received()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("private evidence adapter detail");

        verify(runs).fail(
                generation.received().id(),
                generation.retrieving().revision(),
                "TEACHING_EVIDENCE_RETRIEVAL_FAILED",
                "Teaching evidence could not be loaded; any published chapters remain available");
    }

    @Test
    void classifiesLegacyFullGenerationProviderFailureWithoutExposingProviderDetails() {
        TeachingPlan plan = ownedPlan("alice");
        AssistantRuns runs = mock(AssistantRuns.class);
        GenerationRuns generation = generationRuns(runs, plan.id(), "alice");
        TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
        when(plans.findById(plan.id())).thenReturn(Optional.of(plan));
        DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
        UUID documentVersionId = plan.documentVersionId();
        when(documents.findVersion(documentVersionId))
                .thenReturn(Optional.of(new VersionScope(
                        documentVersionId, UUID.randomUUID(), "READY", "alice")));
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        when(agent.createBase(any(), any(), any(), any()))
                .thenThrow(new ProviderFailureException(
                        new IllegalStateException("private provider response")));
        IllustratedLessonService service = service(
                plans,
                agent,
                mock(IllustratedLessonRepository.class),
                runs,
                documents,
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.generateCandidate(plan.id(), "alice", generation.received()))
                .isInstanceOf(ProviderFailureException.class)
                .hasMessage("teaching model provider failed")
                .hasRootCauseMessage("private provider response");

        verify(runs).fail(
                generation.received().id(),
                generation.retrieving().revision(),
                "TEACHING_MODEL_PROVIDER_FAILED",
                "Teaching model work could not complete; any published chapters remain available");
    }

    @Test
    void keepsThePreviousDurableChapterWhenPublishingNewProgressFails() {
        TeachingPlan plan = ownedPlan("alice");
        AssistantRuns runs = mock(AssistantRuns.class);
        GenerationRuns generation = generationRuns(runs, plan.id(), "alice");
        IllustratedLesson previous = lesson(
                plan.id(), EvidenceStatus.CITED_DRAFT, List.of(4), List.of(UUID.randomUUID()));
        IllustratedLesson next = lesson(
                plan.id(), EvidenceStatus.CITED_DRAFT, List.of(4, 7), List.of(UUID.randomUUID()));
        IllustratedLessonRepository repository = mock(IllustratedLessonRepository.class);
        when(repository.findLatestByPlan(plan.id())).thenReturn(Optional.of(previous));
        IllustratedLessonProgressPublisher publisher = mock(IllustratedLessonProgressPublisher.class);
        when(publisher.publish(next)).thenThrow(new IllegalStateException("private storage coordinates"));
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        when(agent.startBase(any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<IllustratedLesson> publish = invocation.getArgument(3, Consumer.class);
            publish.accept(next);
            return mock(BaseLessonContinuation.class);
        });
        IllustratedLessonService service = service(
                mock(TeachingPlanRepository.class),
                agent,
                repository,
                runs,
                mock(DocumentVersionScopeLookup.class),
                publisher);

        assertThatThrownBy(() -> service.startGeneration(plan, "alice", generation.received()))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("private storage coordinates");

        verify(agent).startBase(eq(plan), eq(generation.received().id()), eq(previous), any());
        verify(runs).fail(
                generation.received().id(),
                generation.retrieving().revision(),
                "TEACHING_PERSISTENCE_FAILED",
                "Teaching progress could not be persisted; any previously published chapters remain available");
        verify(publisher).publish(next);
        assertThat(service.latest(plan.id())).contains(previous);
        verify(repository, never()).save(any());
    }

    @Test
    void classifiesRemainingChapterFailureAsContinuationAndLeavesDurableProgressUntouched() {
        AssistantRuns runs = mock(AssistantRuns.class);
        RunSnapshot retrieving = run(AssistantRunState.RETRIEVING, 5);
        BaseLessonContinuation base = mock(BaseLessonContinuation.class);
        GroundedTeachingAgent agent = mock(GroundedTeachingAgent.class);
        when(agent.continueBaseWorkUnit(eq(base), any()))
                .thenThrow(new IllegalStateException("private continuation state"));
        IllustratedLessonRepository repository = mock(IllustratedLessonRepository.class);
        IllustratedLessonService service = service(
                mock(TeachingPlanRepository.class),
                agent,
                repository,
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(IllustratedLessonProgressPublisher.class));

        assertThatThrownBy(() -> service.continueGeneration(new GenerationContinuation(retrieving, base)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("private continuation state");

        verify(runs).fail(
                retrieving.id(),
                retrieving.revision(),
                "TEACHING_CONTINUATION_FAILED",
                "Remaining teaching chapters could not continue; published chapters remain available");
        verifyNoInteractions(repository);
    }

    @Test
    void recognizesOnlyPersistedSectionsThatAlreadyHaveVerifiableCitations() {
        UUID planId = UUID.randomUUID();
        IllustratedLessonRepository repository = mock(IllustratedLessonRepository.class);
        when(repository.findLatestByPlan(planId)).thenReturn(java.util.Optional.of(lesson(
                planId,
                EvidenceStatus.CITED_DRAFT,
                List.of(4),
                List.of(UUID.randomUUID()))));
        IllustratedLessonService service = service(mock(AssistantRuns.class), repository);

        assertThat(service.hasDurableCitedSection(planId)).isTrue();
    }

    @Test
    void doesNotCallUncitedOrInsufficientWorkDurableProgress() {
        UUID planId = UUID.randomUUID();
        IllustratedLessonRepository repository = mock(IllustratedLessonRepository.class);
        when(repository.findLatestByPlan(planId))
                .thenReturn(java.util.Optional.of(lesson(
                                planId,
                                EvidenceStatus.SUPPORTED,
                                List.of(),
                                List.of(UUID.randomUUID()))) )
                .thenReturn(java.util.Optional.of(lesson(
                        planId,
                        EvidenceStatus.INSUFFICIENT_EVIDENCE,
                        List.of(4),
                        List.of(UUID.randomUUID()))));
        IllustratedLessonService service = service(mock(AssistantRuns.class), repository);

        assertThat(service.hasDurableCitedSection(planId)).isFalse();
        assertThat(service.hasDurableCitedSection(planId)).isFalse();
    }

    private IllustratedLessonService service(AssistantRuns runs) {
        return service(runs, mock(IllustratedLessonRepository.class));
    }

    private IllustratedLessonService service(AssistantRuns runs, IllustratedLessonRepository repository) {
        return service(
                mock(TeachingPlanRepository.class),
                mock(GroundedTeachingAgent.class),
                repository,
                runs,
                mock(DocumentVersionScopeLookup.class),
                mock(IllustratedLessonProgressPublisher.class));
    }

    private IllustratedLessonService service(
            TeachingPlanRepository plans,
            GroundedTeachingAgent agent,
            IllustratedLessonRepository repository,
            AssistantRuns runs,
            DocumentVersionScopeLookup documents,
            IllustratedLessonProgressPublisher progressPublisher) {
        return new IllustratedLessonService(
                plans,
                agent,
                repository,
                runs,
                documents,
                ObservationRegistry.NOOP,
                progressPublisher);
    }

    private TeachingPlan ownedPlan(String owner) {
        TeachingPlan plan = mock(TeachingPlan.class);
        when(plan.id()).thenReturn(UUID.randomUUID());
        when(plan.documentVersionId()).thenReturn(UUID.randomUUID());
        when(plan.createdBy()).thenReturn(owner);
        return plan;
    }

    private GenerationRuns generationRuns(AssistantRuns runs, UUID planId, String owner) {
        RunSnapshot received = run(planId, owner, AssistantRunState.RECEIVED, 1);
        RunSnapshot documentReady = run(
                received.id(), planId, owner, AssistantRunState.DOCUMENT_READINESS, 2);
        RunSnapshot planned = run(
                received.id(), planId, owner, AssistantRunState.LESSON_PLANNING, 3);
        RunSnapshot retrievalPlanned = run(
                received.id(), planId, owner, AssistantRunState.RETRIEVAL_PLANNING, 4);
        RunSnapshot retrieving = run(
                received.id(), planId, owner, AssistantRunState.RETRIEVING, 5);
        when(runs.advance(
                        received.id(), received.revision(), AssistantRunState.DOCUMENT_READINESS,
                        "Rule document readiness is checked"))
                .thenReturn(documentReady);
        when(runs.advance(
                        received.id(), documentReady.revision(), AssistantRunState.LESSON_PLANNING,
                        "Teaching plan is loaded"))
                .thenReturn(planned);
        when(runs.advance(
                        received.id(), planned.revision(), AssistantRunState.RETRIEVAL_PLANNING,
                        "Required lesson evidence is planned"))
                .thenReturn(retrievalPlanned);
        when(runs.advance(
                        received.id(), retrievalPlanned.revision(), AssistantRunState.RETRIEVING,
                        "Allow-listed rule search is running"))
                .thenReturn(retrieving);
        return new GenerationRuns(received, retrieving);
    }

    private record GenerationRuns(RunSnapshot received, RunSnapshot retrieving) {}

    private IllustratedLesson lesson(
            UUID planId,
            EvidenceStatus evidenceStatus,
            List<Integer> sourcePages,
            List<UUID> sourceChunkIds) {
        LessonStep step = new LessonStep(
                1,
                "Do this",
                TeachingMove.DO,
                "Follow the cited rule.",
                sourcePages,
                sourceChunkIds);
        LessonSection section = new LessonSection(
                1,
                "setup",
                List.of("setup"),
                "Setup",
                true,
                evidenceStatus,
                null,
                null,
                List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(),
                planId,
                LessonStatus.DRAFT_READY,
                List.of(section),
                "test",
                Instant.parse("2026-07-23T09:00:00Z"));
    }

    private RunSnapshot run(AssistantRunState state, long revision) {
        return run(UUID.randomUUID(), state, revision);
    }

    private RunSnapshot run(UUID id, AssistantRunState state, long revision) {
        Instant now = Instant.parse("2026-07-23T09:00:00Z");
        return new RunSnapshot(
                id, AssistantRunMode.TEACHING, UUID.randomUUID(), "player", state, revision, now, now,
                state.terminal() ? now : null, null);
    }

    private RunSnapshot run(
            UUID subjectId,
            String owner,
            AssistantRunState state,
            long revision) {
        Instant now = Instant.parse("2026-07-23T09:00:00Z");
        return new RunSnapshot(
                UUID.randomUUID(), AssistantRunMode.TEACHING, subjectId, owner, state, revision, now, now,
                state.terminal() ? now : null, null);
    }

    private RunSnapshot run(
            UUID runId,
            UUID subjectId,
            String owner,
            AssistantRunState state,
            long revision) {
        Instant now = Instant.parse("2026-07-23T09:00:00Z");
        return new RunSnapshot(
                runId, AssistantRunMode.TEACHING, subjectId, owner, state, revision, now, now,
                state.terminal() ? now : null, null);
    }
}
