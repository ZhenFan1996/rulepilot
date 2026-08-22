package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
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
    void withholdsOpaqueTeachingClaimsWhenAnyProtectedRelationCannotBeCorrected() {
        UUID versionId = UUID.randomUUID();
        List<RuleEvidence> evidence = List.of(
                opaqueEvidence(versionId, "Quantity", "The vek keeper seals exactly four luma."),
                opaqueEvidence(versionId, "Multiplier", "For each nari, repeat the toro transfer."),
                opaqueEvidence(versionId, "Timing", "During the pale interval, the vek keeper turns the luma."),
                opaqueEvidence(versionId, "Subject", "The vek keeper opens the nari."),
                opaqueEvidence(versionId, "Negation", "The nari bearer must not open the luma."));
        SectionDraft alteredDraft = new SectionDraft(
                "Opaque procedure",
                VisualKind.FLOW_DIAGRAM,
                "Follow only the cited relations.",
                List.of(evidence.getFirst().chunkId()),
                List.of(
                        new StepDraft(
                                "Quantity",
                                TeachingMove.FLOW,
                                "The vek keeper seals luma.",
                                List.of(evidence.get(0).chunkId())),
                        new StepDraft(
                                "Multiplier",
                                TeachingMove.FLOW,
                                "Perform the toro transfer once.",
                                List.of(evidence.get(1).chunkId())),
                        new StepDraft(
                                "Timing",
                                TeachingMove.FLOW,
                                "After the pale interval, the vek keeper turns the luma.",
                                List.of(evidence.get(2).chunkId())),
                        new StepDraft(
                                "Subject",
                                TeachingMove.FLOW,
                                "The toro keeper opens the nari.",
                                List.of(evidence.get(3).chunkId())),
                        new StepDraft(
                                "Negation",
                                TeachingMove.FLOW,
                                "The nari bearer may open the luma.",
                                List.of(evidence.get(4).chunkId()))));
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return alteredDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                throw new IllegalStateException("correction unavailable");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), evidence, runId, 0, false);
        GeneratedContentCritic critic = (request, risk) -> new Review(
                true,
                List.of(
                        issue(IssueType.MISSING_CRITICAL_RULE, ClaimAspect.QUANTITY, 2, evidence.get(0)),
                        issue(IssueType.MISSING_CRITICAL_RULE, ClaimAspect.MULTIPLIER, 3, evidence.get(1)),
                        issue(IssueType.CONTRADICTION, ClaimAspect.TIMING, 4, evidence.get(2)),
                        issue(IssueType.CONTRADICTION, ClaimAspect.SUBJECT, 5, evidence.get(3)),
                        issue(IssueType.CONTRADICTION, ClaimAspect.NEGATION, 6, evidence.get(4))));
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
            assertThat(section.steps()).singleElement().satisfies(step -> assertThat(step.text()).contains("尚未找到"));
        });
    }

    @Test
    void givesTheWholeLessonCriticEnoughUncitedContextToCheckAWorkedTotal() {
        UUID versionId = UUID.randomUUID();
        UUID unitRuleId = UUID.randomUUID();
        RuleEvidence unitRule = new RuleEvidence(
                unitRuleId, versionId, "SCORING", "Unit value", "Each qualifying object is worth one point.", 7, 7);
        RuleEvidence nearbyDefinition = new RuleEvidence(
                UUID.randomUUID(), versionId, "SCORING", "Qualifying objects", "Stone objects do not qualify.", 7, 7);
        RuleEvidence nearbyCap = new RuleEvidence(
                UUID.randomUUID(), versionId, "SCORING", "Category cap", "At most fifteen objects count.", 7, 7);
        List<RuleEvidence> lowerPriorityNeighbors = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> new RuleEvidence(
                        UUID.randomUUID(),
                        versionId,
                        "SCORING",
                        "Adjacent scoring note " + index,
                        "A different scoring note remains separately scoped.",
                        7,
                        7))
                .toList();
        RuleEvidence aggregation = new RuleEvidence(
                UUID.randomUUID(), versionId, "SCORING", "Per-card repetition",
                "Score the category once for each matching card.", 7, 7);
        RuleEvidence workedTotal = new RuleEvidence(
                UUID.randomUUID(), versionId, "SCORING", "Worked total",
                "Two matching cards and nine qualifying objects score two times nine, for eighteen points.", 7, 7);
        List<RuleEvidence> evidence = new ArrayList<>();
        evidence.add(unitRule);
        evidence.add(nearbyDefinition);
        evidence.add(nearbyCap);
        evidence.addAll(lowerPriorityNeighbors);
        evidence.add(aggregation);
        evidence.add(workedTotal);
        SectionDraft citedDraft = quantitativeDraft(unitRuleId, "这一类一共得到9分。");
        TeachingLessonModel model = request -> citedDraft;
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = quantitativePlan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), evidence, runId, 0, false);
        List<UUID> reviewedEvidence = new ArrayList<>();
        GeneratedContentCritic critic = (request, risk) -> {
            reviewedEvidence.addAll(request.evidence().stream()
                    .map(GeneratedContentCritic.Evidence::chunkId)
                    .toList());
            return new Review(true, List.of());
        };
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(reviewedEvidence).contains(aggregation.chunkId(), workedTotal.chunkId());
    }

    @Test
    void givesASourceContractSectionEveryBoundedRuleGroupInsteadOfStoppingAtSix() {
        UUID versionId = UUID.randomUUID();
        UUID citedId = UUID.randomUUID();
        RuleEvidence cited = new RuleEvidence(
                citedId, versionId, "RULE", "Turn start", "Choose one action at the start of a turn.", 3, 3);
        List<RuleEvidence> earlierGroups = java.util.stream.IntStream.rangeClosed(2, 8)
                .mapToObj(index -> new RuleEvidence(
                        UUID.randomUUID(),
                        versionId,
                        "RULE",
                        "Rule group " + index,
                        "A separately headed rule group explains a distinct non-numeric action.",
                        3,
                        3))
                .toList();
        RuleEvidence ninthGroup = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "Alternative action",
                "Instead of the ordinary action, the player may pass.", 3, 3);
        RuleEvidence tenthGroup = new RuleEvidence(
                UUID.randomUUID(), versionId, "RULE", "End of turn",
                "After the action, play continues with the next player.", 3, 3);
        List<RuleEvidence> evidence = new ArrayList<>();
        evidence.add(cited);
        evidence.addAll(earlierGroups);
        evidence.add(ninthGroup);
        evidence.add(tenthGroup);
        SectionDraft citedDraft = draft(citedId, "选择可用行动并执行。");
        TeachingLessonModel model = request -> citedDraft;
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = sourceContractPlan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), evidence, runId, 0, false);
        List<UUID> reviewedEvidence = new ArrayList<>();
        GeneratedContentCritic critic = (request, risk) -> {
            reviewedEvidence.addAll(request.evidence().stream()
                    .map(GeneratedContentCritic.Evidence::chunkId)
                    .toList());
            return new Review(true, List.of());
        };
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(reviewedEvidence).contains(ninthGroup.chunkId(), tenthGroup.chunkId());
    }

    @Test
    void withholdsAnInitialQuantitativeDraftWhenItsRequiredIndependentReviewCannotRun() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                "Per-card scoring",
                "Each matching card scores nine points.",
                7,
                7);
        SectionDraft citedDraft = quantitativeDraft(evidenceId, "每张同类卡得到九分。");
        TeachingLessonModel model = request -> citedDraft;
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = quantitativePlan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), List.of(evidence), runId, 0, false);
        GeneratedContentCritic unavailableCritic = (request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        };
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        unavailableCritic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void withholdsAnInitialQuantitativeDraftWhenTheCriticReportsThatItSkippedReview() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                "Per-card scoring",
                "Each matching card scores nine points.",
                7,
                7);
        SectionDraft citedDraft = quantitativeDraft(evidenceId, "每张同类卡得到九分。");
        TeachingLessonModel model = request -> citedDraft;
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = quantitativePlan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), List.of(evidence), runId, 0, false);
        GeneratedContentCritic skippedCritic = (request, risk) -> new Review(false, List.of());
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        skippedCritic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void retainsANonQuantitativeCitedDraftWhenTheOptionalInitialReviewCannotRun() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "FLOW",
                "Turn handoff",
                "After resolving the action, pass the marker to the next player.",
                7,
                7);
        SectionDraft citedDraft = draft(evidenceId, "行动结算后，把标记交给下一位玩家。");
        TeachingLessonModel model = request -> citedDraft;
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), List.of(evidence), runId, 0, false);
        GeneratedContentCritic unavailableCritic = (request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        };
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        unavailableCritic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT));
    }

    @Test
    void withholdsASectionWhenAKnownFactualDefectCannotBeCorrected() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                "Per-card scoring",
                "Score the category once for each matching card. Two cards worth nine points each score eighteen.",
                7,
                7);
        SectionDraft citedDraft = draft(evidenceId, "这一类一共得到9分。");
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return citedDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                throw new IllegalStateException("correction provider unavailable");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), List.of(evidence), runId, 0, false);
        GeneratedContentCritic critic = (request, risk) -> new Review(
                true,
                List.of(new Issue(
                        IssueType.CONTRADICTION,
                        2,
                        List.of(evidenceId),
                        "The repeated per-card score was collapsed into one subtotal.")));
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
    }

    @Test
    void withholdsACorrectionWhenItsRequiredIndependentReviewCannotRun() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                "Per-card scoring",
                "Score the category once for each matching card. Two cards worth nine points each score eighteen.",
                7,
                7);
        SectionDraft citedDraft = draft(evidenceId, "这一类一共得到9分。");
        TeachingLessonModel model = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return citedDraft;
            }

            @Override
            public SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                return draft(evidenceId, "每张同类卡分别得到9分；两张合计18分。");
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                model, new PolicyEvidenceVerifier(), invocations, VisualRulebookPageFacts.empty());
        TeachingPlan plan = plan(versionId);
        UUID runId = UUID.randomUUID();
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan, plan.sections().getFirst(), List.of(), List.of(evidence), runId, 0, false);
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> {
            if (reviews.getAndIncrement() == 0) {
                return new Review(
                        true,
                        List.of(new Issue(
                                IssueType.CONTRADICTION,
                                2,
                                List.of(evidenceId),
                                "The repeated per-card score was collapsed into one subtotal.")));
            }
            throw new AgentExecutionStoppedException(StopReason.MODEL_BUDGET);
        };
        List<LessonSection> published = new ArrayList<>(List.of(candidate.section()));

        new TeachingPublishedLessonReviewer(
                        critic, invocations, composer, new TeachingReviewCorrectionPolicy())
                .review(plan, List.of(candidate), published, runId, () -> {});

        assertThat(published).singleElement().satisfies(section ->
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
        assertThat(reviews).hasValue(2);
    }

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
    void withholdsAChangedCorrectionThatNeverPassesAnIndependentReview() {
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
                assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE));
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

    private TeachingPlan sourceContractPlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "source-page-3",
                        "第三页规则",
                        "Teach every independently headed rule group on this source page.",
                        true,
                        true,
                        List.of("turn start", "ordinary action", "alternative action", "end of turn"),
                        List.of(
                                "core_loop",
                                TeachingSourceCoverageContract.CONTRACT_VERSION_TAG,
                                TeachingSourceCoverageContract.roleTag(SourceCoverageRole.LEGAL_ACTION)),
                        List.of(3))),
                "player",
                Instant.now());
    }

    private TeachingPlan quantitativePlan(UUID versionId) {
        return new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "scoring-ledger",
                        "计分账本",
                        "Teach the complete source-bound scoring calculation.",
                        true,
                        false,
                        List.of("source-bound scoring calculation"),
                        List.of(TeachingSourceCoverageContract.roleTag(SourceCoverageRole.SCORING)))),
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

    private SectionDraft quantitativeDraft(UUID evidenceId, String text) {
        return new SectionDraft(
                "计分账本",
                VisualKind.SCOREBOARD,
                "逐项核对规则书中的计分关系。",
                List.of(evidenceId),
                List.of(new StepDraft("核算结果", TeachingMove.LEDGER, text, List.of(evidenceId))));
    }

    private RuleEvidence opaqueEvidence(UUID versionId, String heading, String excerpt) {
        return new RuleEvidence(UUID.randomUUID(), versionId, "OPAQUE", heading, excerpt, 6, 6);
    }

    private Issue issue(IssueType type, ClaimAspect aspect, int claimPosition, RuleEvidence evidence) {
        return new Issue(
                type,
                aspect,
                claimPosition,
                List.of(evidence.chunkId()),
                "The generated relation does not preserve the directly cited source fact.");
    }
}
