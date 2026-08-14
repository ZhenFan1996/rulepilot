package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeachingPublishedLessonReviewerTest {

    @Test
    void acceptsAModelCorrectionOnlyAfterTheFollowupCriticApprovesIt() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "END",
                "End check",
                "If the marker reaches the final space, the game ends. If nobody has won, prepare the next round: "
                        + "return the marker and begin a new round.",
                9,
                9);
        SectionDraft citedDraft = draft(
                evidenceId,
                "如果终局条件未触发，则准备下一轮：放回标记，然后开始新一轮。");
        SectionDraft criticCorrection = draft(
                evidenceId,
                "终局条件未触发时，准备下一轮：放回标记并开始新一轮。");
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return citedDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                return revisions.getAndIncrement() == 0
                        ? criticCorrection
                        : draft(evidenceId, "终局条件未触发时，放回标记并开始新一轮。");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                runId,
                0,
                false);
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> reviews.getAndIncrement() == 0
                ? new Review(
                        true,
                        List.of(new Issue(
                                IssueType.CONTRADICTION,
                                2,
                                List.of(evidenceId),
                                "The continuation condition should be changed.")))
                : new Review(true, List.of());
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps()).singleElement().satisfies(step -> assertThat(step.text())
                    .startsWith("终局条件未触发时，准备下一轮：")
                    .doesNotContain("终局条件已触发"));
        });
        assertThat(reviews).hasValue(2);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void doesNotCallAChangedCorrectionSupportedUntilASecondReviewAcceptsIt() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "END",
                "End check",
                "If the marker reaches the final space, the game ends. If nobody has won, prepare the next round: "
                        + "return the marker and begin a new round.",
                4,
                4);
        SectionDraft citedDraft = draft(
                evidenceId,
                "如果终局条件未触发，则准备下一轮：放回标记，然后开始新一轮。");
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return citedDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                return draft(
                        evidenceId,
                        revisions.incrementAndGet() == 1
                                ? "如果终局条件未触发，则准备下一轮：先放回标记，再开始新一轮。"
                                : "终局条件未触发时，准备下一轮：放回标记并开始新一轮。");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                runId,
                0,
                false);
        GeneratedContentCritic critic = (request, risk) -> new Review(
                true,
                List.of(new Issue(
                        IssueType.UNSUPPORTED_CLAIM,
                        2,
                        List.of(evidenceId),
                        "The continuation wording remains unsupported.")));
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT));
        assertThat(revisions).hasValue(4);
    }

    @Test
    void givesTheThirdBoundedCorrectionOneFinalIndependentAcceptanceReview() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "FLOW",
                "Turn flow",
                "After resolving the action, pass the marker and begin the next turn.",
                8,
                8);
        SectionDraft citedDraft = draft(evidenceId, "结算行动后，开始下一回合。");
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return citedDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                int revision = revisions.incrementAndGet();
                return draft(evidenceId, "结算行动后，传递标记并开始下一回合（修订" + revision + "）。");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                runId,
                0,
                false);
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> reviews.incrementAndGet() <= 3
                ? new Review(
                        true,
                        List.of(new Issue(
                                IssueType.MISSING_CRITICAL_RULE,
                                2,
                                List.of(evidenceId),
                                "The next bounded correction must be independently reviewed.")))
                : new Review(true, List.of());
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps().getFirst().text()).contains("修订3");
        });
        assertThat(revisions).hasValue(3);
        assertThat(reviews).hasValue(4);
    }

    @Test
    void treatsACitationOnlyRepairAsARealChangeButStillRequiresSecondReview() {
        UUID versionId = UUID.randomUUID();
        UUID governingId = UUID.randomUUID();
        UUID unrelatedId = UUID.randomUUID();
        RuleEvidence governing = new RuleEvidence(
                governingId,
                versionId,
                "END",
                "End check",
                "If nobody has won, return the marker and begin a new round.",
                9,
                9);
        RuleEvidence unrelated = new RuleEvidence(
                unrelatedId,
                versionId,
                "COMPONENTS",
                "Marker",
                "Each player receives one marker.",
                2,
                2);
        SectionDraft base = draft(
                governingId,
                "如果终局条件未触发，则准备下一轮：放回标记，然后开始新一轮。");
        SectionDraft overCited = new SectionDraft(
                base.title(),
                base.visualKind(),
                base.visualCaption(),
                List.of(governingId, unrelatedId),
                base.steps());
        SectionDraft corrected = new SectionDraft(
                base.title(),
                base.visualKind(),
                base.visualCaption(),
                List.of(governingId),
                base.steps());
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return overCited;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return corrected;
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(governing, unrelated),
                runId,
                0,
                false);
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> reviews.getAndIncrement() == 0
                ? new Review(
                        true,
                        List.of(new Issue(
                                IssueType.UNSUPPORTED_CLAIM,
                                1,
                                List.of(unrelatedId),
                                "The visual caption includes an irrelevant citation.")))
                : new Review(true, List.of());
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.visualSourceChunkIds()).containsExactly(governingId);
        });
        assertThat(revisions).hasValue(1);
        assertThat(reviews).hasValue(2);
    }

    private TeachingPlan plan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "ending",
                        "结束流程",
                        "Teach the exclusive terminal and continuation branches.",
                        true,
                        false,
                        List.of("end condition", "next round"),
                        List.of("end"))),
                "player",
                Instant.now());
    }

    private SectionDraft draft(UUID evidenceId, String text) {
        return new SectionDraft(
                "结束流程",
                VisualKind.FLOW_DIAGRAM,
                "检查结束条件，再选择唯一适用的分支。",
                List.of(evidenceId),
                List.of(new StepDraft("准备下一轮", TeachingMove.FLOW, text, List.of(evidenceId))));
    }
}
