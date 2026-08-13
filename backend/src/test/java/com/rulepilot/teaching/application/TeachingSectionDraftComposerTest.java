package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AssistantReadTools.RulePageImage;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.InputTokenProfile;
import com.rulepilot.teaching.TeachingLessonModel.InvalidOutputException;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class TeachingSectionDraftComposerTest {

    @Test
    void givesTheModelVisualPageFactsAlongsideTheCitedSourceImage() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<TeachingLessonModel.SectionRequest> requests = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                requests.add(request);
                return visualDraft(chunkId);
            }
        };
        VisualRulebookPageFacts facts = pageFacts(4);
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), new ImmediateAuditedAgentInvocations(), facts);
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                true);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.pageImages()).extracting(TeachingLessonModel.PageImageInput::pageNumber).containsExactly(4);
            assertThat(request.evidence()).singleElement().satisfies(source ->
                    assertThat(source.excerpt())
                            .contains("Visual presentation data only", "Cataloged visual anchors")
                            .doesNotContain("The central board shows the shared setup area"));
        });
        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(candidate.section().visualSourcePages()).containsExactly(4);
    }

    @Test
    void usesOneExplicitCitedTextFallbackWithoutAnAdapterInternalRetry() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<TeachingLessonModel.SectionRequest> requests = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                requests.add(request);
                if (!request.pageImages().isEmpty()) {
                    throw new IllegalStateException("provider unavailable");
                }
                return textDraft(chunkId);
            }
        };
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model,
                new PolicyEvidenceVerifier(),
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                true);

        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().pageImages()).isNotEmpty();
        assertThat(requests.getLast().pageImages()).isEmpty();
        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void fallsBackToCitedTextAfterBothVisualContractAttemptsAreMalformed() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<TeachingLessonModel.SectionRequest> requests = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public boolean supportsVisualEvidence() {
                return true;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                requests.add(request);
                if (!request.pageImages().isEmpty()) {
                    throw new InvalidOutputException("malformed visual response", null);
                }
                return textDraft(chunkId);
            }
        };
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model,
                new PolicyEvidenceVerifier(),
                new ImmediateAuditedAgentInvocations(),
                VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                true);

        assertThat(requests).hasSize(3);
        assertThat(requests.subList(0, 2)).allSatisfy(request -> assertThat(request.pageImages()).isNotEmpty());
        assertThat(requests.getLast().pageImages()).isEmpty();
        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void accountsForAContractRepairAsASeparateModelInvocation() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<String> attempts = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                attempts.add("compose");
                throw new InvalidOutputException("malformed response", null);
            }

            @Override
            public SectionDraft repairCompositionContract(SectionRequest request) {
                attempts.add("contract-repair");
                return textDraft(chunkId);
            }
        };
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);

        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                false);

        assertThat(candidate.section().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(attempts).containsExactly("compose", "contract-repair");
        assertThat(invocations.modelOperations)
                .containsExactly("composeTeachingSection|1", "repairTeachingSectionContract|1");
    }

    @Test
    void accountsForARevisionContractRepairWithoutHidingItInsideTheRevisionActivity() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        List<String> attempts = new ArrayList<>();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return textDraft(chunkId);
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                attempts.add("revise");
                throw new InvalidOutputException("malformed revision", null);
            }

            @Override
            public SectionDraft repairRevisionContract(
                    SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                attempts.add("revision-contract-repair");
                return textDraft(chunkId);
            }
        };
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        TeachingLessonModel.SectionRequest request = new TeachingSectionModelRequestFactory(
                        VisualRulebookPageFacts.empty())
                .create(plan, plan.sections().getFirst(), List.of(), List.of(evidence), false, false);

        SectionDraft revised = composer.reviseModelDraft(
                UUID.randomUUID(),
                plan.sections().getFirst(),
                request,
                textDraft(chunkId),
                List.of("Repair the output contract."),
                "correctTeachingSection",
                "repairTeachingSectionCorrectionContract",
                "Teaching correction received");

        assertThat(revised).isEqualTo(textDraft(chunkId));
        assertThat(attempts).containsExactly("revise", "revision-contract-repair");
        assertThat(invocations.modelOperations)
                .containsExactly("correctTeachingSection|1", "repairTeachingSectionCorrectionContract|1");
    }

    @Test
    void budgetsAndAuditsTheCompleteProviderRequestProfile() {
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, versionId);
        InputTokenProfile profile = new InputTokenProfile(
                "qwen",
                148,
                80,
                10,
                9,
                24,
                11,
                0,
                0,
                14);
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public InputTokenProfile compositionInputProfile(SectionRequest request) {
                return profile;
            }

            @Override
            public SectionDraft compose(SectionRequest request) {
                return textDraft(chunkId);
            }

            @Override
            public int estimatedOutputTokens(SectionRequest request, SectionDraft draft) {
                return 37;
            }
        };
        RecordingInvocations invocations = new RecordingInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);

        composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                UUID.randomUUID(),
                0,
                false);

        assertThat(invocations.inputTokens).containsExactly(148);
        assertThat(invocations.outputTokens).containsExactly(37);
        assertThat(invocations.summaries)
                .singleElement()
                .asString()
                .contains(
                        "p=qwen",
                        "f=80",
                        "o=10",
                        "r=9",
                        "e=24",
                        "s=11",
                        "c=0",
                        "v=0",
                        "x=14");
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

    private RuleEvidence evidence(UUID chunkId, UUID versionId) {
        return new RuleEvidence(
                chunkId,
                versionId,
                "SETUP",
                "Central board",
                "Place the central board in the middle of the table before the first turn.",
                4,
                4,
                List.of(new RulePageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 1_000, 1_000)));
    }

    private SectionDraft visualDraft(UUID chunkId) {
        return new SectionDraft(
                "摆好中央展示区",
                VisualKind.TABLE_LAYOUT,
                "先在图中找到主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "放置主棋盘",
                        TeachingMove.VISUAL,
                        "在图中找到主棋盘，再把它放在桌面中央。",
                        List.of(chunkId),
                        new VisualFocusDraft(4, "主棋盘", 120, 120, 500, 500))));
    }

    private SectionDraft textDraft(UUID chunkId) {
        return new SectionDraft(
                "摆好中央展示区",
                VisualKind.REFERENCE_CARD,
                "先摆好主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "放置主棋盘",
                        TeachingMove.DO,
                        "把主棋盘放在桌面中央。",
                        List.of(chunkId))));
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

    private static final class RecordingInvocations implements AuditedAgentInvocations {

        private final List<String> modelOperations = new ArrayList<>();
        private final List<Integer> inputTokens = new ArrayList<>();
        private final List<String> summaries = new ArrayList<>();
        private final List<Integer> outputTokens = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelOperations.add(operation);
            inputTokens.add(estimatedInputTokens);
            summaries.add(successSummary);
            T result = invocation.get();
            outputTokens.add(outputTokenEstimator.applyAsInt(result));
            return result;
        }
    }
}
