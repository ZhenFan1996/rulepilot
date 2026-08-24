package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AssistantRuns.WorkloadDemand;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.ContentCriticModel.CritiqueDraft;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.application.ConditionalGeneratedContentCritic;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GroundedTeachingAgentWorkloadTest {

    @Test
    void completesNineteenConcurrentSectionsWithinTheAdmittedCallDemand() {
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Map<Integer, UUID> searchEvidenceIds = IntStream.rangeClosed(1, 19)
                .boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(position -> position, ignored -> UUID.randomUUID()));
        Map<Integer, UUID> canonicalEvidenceIds = IntStream.rangeClosed(1, 19)
                .boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(position -> position, ignored -> UUID.randomUUID()));
        WorkflowTools tools = new WorkflowTools(versionId, searchEvidenceIds, canonicalEvidenceIds);
        EnforcingInvocations invocations = new EnforcingInvocations();
        WorkflowModel model = new WorkflowModel();
        GeneratedContentCritic critic = new ConditionalGeneratedContentCritic(
                request -> new CritiqueDraft(List.of()), invocations, false);
        NativeToolScopes scopes = (owner, documentVersionId, assistantRunId) -> Optional.of(
                new com.rulepilot.assistant.NativeAgentTool.ToolScope(
                        owner, documentVersionId, assistantRunId, Instant.now().plusSeconds(30)));
        var refiner = new TeachingSourcePageEvidenceRefiner(
                scopes, tools, new PolicyEvidenceVerifier(), invocations);
        GroundedTeachingAgent agent = new GroundedTeachingAgent(
                tools,
                model,
                new PolicyEvidenceVerifier(),
                critic,
                invocations,
                VisualRulebookPageFacts.empty(),
                VisualRulebookPageCatalogModel.unavailable(),
                3,
                3,
                refiner);
        TeachingPlan plan = plan(versionId);
        WorkloadDemand demand = agent.workload(plan);
        invocations.admit(demand);

        var lesson = agent.createBase(plan, runId, null, ignored -> {});

        assertThat(lesson.sections()).hasSize(19).allSatisfy(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED));
        assertThat(model.maximumConcurrentCalls()).isGreaterThanOrEqualTo(3);
        assertThat(model.attempts("topic-7")).isEqualTo(2);
        assertThat(tools.searches()).isEqualTo(57);
        assertThat(tools.visualPageReads()).isEqualTo(19);
        assertThat(tools.canonicalFallbackReads()).isOne();
        assertThat(invocations.usedToolCalls()).isEqualTo(77).isLessThanOrEqualTo(demand.requiredToolCalls());
        assertThat(invocations.usedModelCalls()).isEqualTo(21).isLessThanOrEqualTo(demand.requiredModelCalls());
        // Admission reserves both audited interpretation attempts for every bound page even though this fixture's
        // catalog is unavailable. Runtime availability and cached facts may reduce work, never its safe upper bound.
        assertThat(demand).isEqualTo(new WorkloadDemand(95, 115));
    }

    static TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Workload fixture",
                "Teach independently sourced relations in plan order.",
                IntStream.rangeClosed(1, 19)
                        .mapToObj(position -> new TeachingPlan.PlannedSection(
                                position,
                                "topic-" + position,
                                "Topic " + position,
                                "Teach the bounded relation on source page " + position + ".",
                                true,
                                true,
                                List.of(
                                        "intent-a-" + position,
                                        "intent-b-" + position,
                                        "intent-c-" + position),
                                List.of("source_coverage"),
                                List.of(position)))
                        .toList(),
                "player",
                Instant.now());
    }

    static final class WorkflowTools implements AssistantReadTools {
        private final UUID versionId;
        private final Map<Integer, UUID> searchEvidenceIds;
        private final Map<Integer, UUID> canonicalEvidenceIds;
        private final Map<String, Integer> pagesByQuery;
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger visualPageReads = new AtomicInteger();
        private final AtomicInteger canonicalFallbackReads = new AtomicInteger();

        WorkflowTools(
                UUID versionId,
                Map<Integer, UUID> searchEvidenceIds,
                Map<Integer, UUID> canonicalEvidenceIds) {
            this.versionId = versionId;
            this.searchEvidenceIds = searchEvidenceIds;
            this.canonicalEvidenceIds = canonicalEvidenceIds;
            this.pagesByQuery = IntStream.rangeClosed(1, 19)
                    .boxed()
                    .flatMap(page -> List.of("intent-a-" + page, "intent-b-" + page, "intent-c-" + page).stream()
                            .map(query -> Map.entry(query, page)))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            searches.incrementAndGet();
            int page = pagesByQuery.get(request.query());
            return List.of(new RuleEvidence(
                    searchEvidenceIds.get(page),
                    versionId,
                    "RULE",
                    "Topic " + page,
                    "Image-only source page " + page,
                    page,
                    page,
                    List.of(),
                    RuleEvidence.ContentKind.VISUAL_PLACEHOLDER));
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            int page = pageNumbers.iterator().next();
            if (includePageImages) {
                visualPageReads.incrementAndGet();
                if (page == 5) throw new IllegalStateException("visual storage temporarily unavailable");
            } else {
                canonicalFallbackReads.incrementAndGet();
            }
            return List.of(canonical(page, includePageImages));
        }

        private RuleEvidence canonical(int page, boolean includePageImages) {
            return new RuleEvidence(
                    canonicalEvidenceIds.get(page),
                    versionId,
                    "RULE",
                    "Topic " + page,
                    "Perform the bounded action for topic " + page + ".",
                    page,
                    page,
                    includePageImages
                            ? List.of(new RulePageImage(page, "image/png", new byte[] {(byte) page}, 100, 80))
                            : List.of(),
                    RuleEvidence.ContentKind.CANONICAL_TEXT);
        }

        int searches() {
            return searches.get();
        }

        int visualPageReads() {
            return visualPageReads.get();
        }

        int canonicalFallbackReads() {
            return canonicalFallbackReads.get();
        }
    }

    static final class WorkflowModel implements TeachingLessonModel {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private final CountDownLatch concurrentStart = new CountDownLatch(3);

        @Override
        public SectionDraft compose(SectionRequest request) {
            int running = active.incrementAndGet();
            maximumActive.accumulateAndGet(running, Math::max);
            try {
                if (Set.of("topic-2", "topic-3", "topic-4").contains(request.topicKey())) {
                    concurrentStart.countDown();
                    if (!concurrentStart.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("section calls did not execute concurrently");
                    }
                }
                int attempt = attempts.computeIfAbsent(request.topicKey(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (request.topicKey().equals("topic-7") && attempt == 1) {
                    throw new InvalidOutputException("malformed structured section", null);
                }
                UUID evidenceId = request.evidence().getFirst().chunkId();
                return new SectionDraft(
                        request.title(),
                        VisualKind.REFERENCE_CARD,
                        "Use the cited bounded relation.",
                        List.of(evidenceId),
                        List.of(new StepDraft(
                                "Apply " + request.title(),
                                TeachingMove.DO,
                                "Perform the bounded action described by the cited source.",
                                List.of(evidenceId))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent section fixture was interrupted", interrupted);
            } finally {
                active.decrementAndGet();
            }
        }

        int attempts(String topicKey) {
            return attempts.getOrDefault(topicKey, new AtomicInteger()).get();
        }

        int maximumConcurrentCalls() {
            return maximumActive.get();
        }
    }

    private static final class EnforcingInvocations implements AuditedAgentInvocations {
        private final AtomicInteger usedToolCalls = new AtomicInteger();
        private final AtomicInteger usedModelCalls = new AtomicInteger();
        private volatile WorkloadDemand demand;

        void admit(WorkloadDemand demand) {
            this.demand = demand;
        }

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            WorkloadDemand admitted = demand;
            if (admitted == null) throw new IllegalStateException("workload was not admitted");
            if (type == ActivityType.TOOL
                    && usedToolCalls.incrementAndGet() > admitted.requiredToolCalls()) {
                throw new AssertionError("tool workload exceeded its admitted demand");
            }
            if ((type == ActivityType.MODEL || type == ActivityType.CRITIC)
                    && usedModelCalls.incrementAndGet() > admitted.requiredModelCalls()) {
                throw new AssertionError("model workload exceeded its admitted demand");
            }
            return invocation.get();
        }

        int usedToolCalls() {
            return usedToolCalls.get();
        }

        int usedModelCalls() {
            return usedModelCalls.get();
        }
    }
}
