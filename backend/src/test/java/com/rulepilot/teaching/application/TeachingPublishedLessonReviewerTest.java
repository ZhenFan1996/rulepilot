package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.ClaimAspect;
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
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TeachingPublishedLessonReviewerTest {

    @Test
    void promotesACleanCitedDraftWithoutLosingItsPublishedVisual() {
        Fixture fixture = fixture();
        VisualFocus focus = new VisualFocus(
                4,
                "计分区",
                "页面区域显示每个标记的得分关系。",
                120,
                180,
                500,
                300,
                VisualSourceKind.PAGE_REGION);
        LessonSection citedWithVisual = withVisual(
                new TeachingBaseSectionPublicationPolicy().publish(fixture.candidate()),
                focus);
        List<LessonSection> published = new ArrayList<>(List.of(citedWithVisual));
        AtomicInteger progress = new AtomicInteger();

        new TeachingPublishedLessonReviewer(
                        (request, risk) -> new Review(true, List.of()),
                        fixture.invocations(),
                        fixture.composer())
                .review(
                        fixture.plan(),
                        List.of(fixture.candidate()),
                        published,
                        fixture.runId(),
                        progress::incrementAndGet);

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.steps().getFirst().visualFoci()).containsExactly(focus);
        });
        assertThat(progress).hasValue(1);
    }

    @Test
    void retainsTheCompleteCitedDraftWhenTheReviewerIsUnavailable() {
        Fixture fixture = fixture();
        LessonSection cited = new TeachingBaseSectionPublicationPolicy().publish(fixture.candidate());
        List<LessonSection> published = new ArrayList<>(List.of(cited));
        AtomicInteger progress = new AtomicInteger();
        GeneratedContentCritic unavailable = (request, risk) -> {
            throw new IllegalStateException("critic unavailable");
        };

        new TeachingPublishedLessonReviewer(unavailable, fixture.invocations(), fixture.composer())
                .review(
                        fixture.plan(),
                        List.of(fixture.candidate()),
                        published,
                        fixture.runId(),
                        progress::incrementAndGet);

        assertThat(published).containsExactly(cited);
        assertThat(published.getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
        assertThat(progress).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void propagatesAWholeRunStopFromTheFinalReviewerWhileRetainingTheCitedDraft(StopReason stopReason) {
        Fixture fixture = fixture();
        LessonSection cited = new TeachingBaseSectionPublicationPolicy().publish(fixture.candidate());
        List<LessonSection> published = new ArrayList<>(List.of(cited));
        GeneratedContentCritic stoppedReviewer = (request, risk) -> {
            throw new AgentExecutionStoppedException(stopReason);
        };

        assertThatThrownBy(() -> new TeachingPublishedLessonReviewer(
                                stoppedReviewer, fixture.invocations(), fixture.composer())
                        .review(
                                fixture.plan(),
                                List.of(fixture.candidate()),
                                published,
                                fixture.runId(),
                                () -> {}))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue("reason", stopReason);

        assertThat(published).containsExactly(cited);
        assertThat(published.getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.CITED_DRAFT);
    }

    @Test
    void withholdsOnlyAnAtomicallyConfirmedDefectiveWholeChapter() {
        Fixture fixture = fixture();
        List<LessonSection> published = new ArrayList<>(List.of(
                new TeachingBaseSectionPublicationPolicy().publish(fixture.candidate())));
        GeneratedContentCritic confirmedDefect = (request, risk) -> {
            var claim = request.claims().getFirst();
            return new Review(true, List.of(new Issue(
                    IssueType.CONTRADICTION,
                    ClaimAspect.QUANTITY,
                    claim.position(),
                    claim.citationIds(),
                    "The cited quantity does not support the published relation.")));
        };

        new TeachingPublishedLessonReviewer(confirmedDefect, fixture.invocations(), fixture.composer())
                .review(
                        fixture.plan(),
                        List.of(fixture.candidate()),
                        published,
                        fixture.runId(),
                        () -> {});

        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
            assertThat(section.steps()).singleElement().satisfies(step -> assertThat(step.text()).contains("尚未找到"));
        });
    }

    @Test
    void returnsAConfirmedDefectForOneCompleteReplacementAndIndependentlyAcceptsIt() {
        Fixture fixture = fixture();
        UUID evidenceId = fixture.candidate().evidence().getFirst().chunkId();
        SectionDraft replacement = new SectionDraft(
                "按规则重新计算",
                VisualKind.SCOREBOARD,
                "修正后的完整章节说明每个符合条件的标记都独立计四分。",
                List.of(evidenceId),
                List.of(new StepDraft(
                        "逐个核对并计分",
                        TeachingMove.DO,
                        "逐个检查符合条件的标记，每个标记获得四分。",
                        List.of(evidenceId))));
        AtomicReference<List<String>> receivedFeedback = new AtomicReference<>();
        AtomicInteger revisions = new AtomicInteger();
        TeachingLessonModel replacementModel = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return fixture.candidate().draft();
            }

            @Override
            public SectionDraft revise(
                    SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                receivedFeedback.set(List.copyOf(feedback));
                return replacement;
            }
        };
        TeachingSectionDraftComposer replacementComposer = new TeachingSectionDraftComposer(
                replacementModel,
                new PolicyEvidenceVerifier(),
                fixture.invocations(),
                VisualRulebookPageFacts.empty());
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> {
            if (reviews.incrementAndGet() == 1) {
                return new Review(true, List.of(new Issue(
                        IssueType.CONTRADICTION,
                        ClaimAspect.QUANTITY,
                        request.claims().getFirst().position(),
                        request.claims().getFirst().citationIds(),
                        "The published quantity relation is wrong.")));
            }
            return new Review(true, List.of());
        };
        VisualFocus obsoleteVisual = new VisualFocus(
                4,
                "旧图",
                "这张图属于被替换的旧章节。",
                120,
                180,
                500,
                300,
                VisualSourceKind.PAGE_REGION);
        List<LessonSection> published = new ArrayList<>(List.of(withVisual(
                new TeachingBaseSectionPublicationPolicy().publish(fixture.candidate()),
                obsoleteVisual)));

        TeachingPublishedLessonReviewer.ReviewResult result = new TeachingPublishedLessonReviewer(
                        critic, fixture.invocations(), replacementComposer)
                .review(
                        fixture.plan(),
                        List.of(fixture.candidate()),
                        published,
                        fixture.runId(),
                        () -> {});

        assertThat(reviews).hasValue(2);
        assertThat(revisions).hasValue(1);
        assertThat(receivedFeedback.get()).singleElement().asString()
                .contains("type=CONTRADICTION", "aspect=QUANTITY", evidenceId.toString(), "COMPLETE replacement");
        assertThat(result.acceptedReplacementIndexes()).containsExactly(0);
        assertThat(published).singleElement().satisfies(section -> {
            assertThat(section.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
            assertThat(section.title()).isEqualTo(replacement.title());
            assertThat(section.steps().getFirst().text()).isEqualTo(replacement.steps().getFirst().text());
            assertThat(section.steps().getFirst().visualFoci()).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(value = StopReason.class, names = {"MODEL_BUDGET", "CANCELLED"})
    void persistsConfirmedDefectsAsUnreadableBeforeAReplacementStop(StopReason stopReason) {
        UUID versionId = UUID.randomUUID();
        UUID firstEvidenceId = UUID.randomUUID();
        UUID secondEvidenceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RuleEvidence firstEvidence = evidence(
                firstEvidenceId, versionId, "First relation", "Each matching marker is worth four points.", 4);
        RuleEvidence secondEvidence = evidence(
                secondEvidenceId, versionId, "Second relation", "Each completed row is worth two points.", 5);
        SectionDraft firstDraft = draft(
                "计算标记得分", "每个符合条件的标记计四分。", firstEvidenceId);
        SectionDraft secondDraft = draft(
                "计算行得分", "每个完成的行计两分。", secondEvidenceId);
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "计分教学",
                "分别核对两种计分关系。",
                List.of(
                        planned(1, "marker-scoring", "标记得分"),
                        planned(2, "row-scoring", "行得分")),
                "player",
                Instant.now());
        TeachingLessonModel stoppingModel = new TeachingLessonModel() {
            @Override
            public SectionDraft compose(SectionRequest request) {
                return request.topicKey().equals("marker-scoring") ? firstDraft : secondDraft;
            }

            @Override
            public SectionDraft revise(
                    SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
                throw new AgentExecutionStoppedException(stopReason);
            }
        };
        var invocations = new ImmediateAuditedAgentInvocations();
        var composer = new TeachingSectionDraftComposer(
                stoppingModel,
                new PolicyEvidenceVerifier(),
                invocations,
                VisualRulebookPageFacts.empty());
        TeachingSectionDraftCandidate first = composer.compose(
                plan, plan.sections().get(0), List.of(), List.of(firstEvidence), runId, 0, false);
        TeachingSectionDraftCandidate second = composer.compose(
                plan, plan.sections().get(1), List.of(), List.of(secondEvidence), runId, 1, false);
        List<LessonSection> published = new ArrayList<>(List.of(
                new TeachingBaseSectionPublicationPolicy().publish(first),
                new TeachingBaseSectionPublicationPolicy().publish(second)));
        AtomicReference<List<LessonSection>> persisted = new AtomicReference<>();
        AtomicInteger persistedSnapshots = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> new Review(true, List.of(new Issue(
                IssueType.CONTRADICTION,
                ClaimAspect.QUANTITY,
                request.claims().getFirst().position(),
                request.claims().getFirst().citationIds(),
                "The first published quantity is contradicted by its evidence.")));

        assertThatThrownBy(() -> new TeachingPublishedLessonReviewer(critic, invocations, composer)
                        .review(
                                plan,
                                List.of(first, second),
                                published,
                                runId,
                                () -> {
                                    persisted.set(List.copyOf(published));
                                    persistedSnapshots.incrementAndGet();
                                }))
                .isInstanceOf(AgentExecutionStoppedException.class)
                .hasFieldOrPropertyWithValue("reason", stopReason);

        assertThat(persistedSnapshots).hasValue(1);
        assertThat(persisted.get()).containsExactlyElementsOf(published);
        assertThat(published.get(0).evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(published.get(0).steps().getFirst().text()).contains("尚未找到");
        assertThat(published.get(1).evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(published.get(1).steps().getFirst().text())
                .isEqualTo(secondDraft.steps().getFirst().text());
    }

    private static Fixture fixture() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                "Marker value",
                "Each matching marker is worth exactly four points.",
                4,
                4);
        SectionDraft draft = new SectionDraft(
                "计算标记得分",
                VisualKind.SCOREBOARD,
                "每个符合条件的标记计 4 分。",
                List.of(evidenceId),
                List.of(new StepDraft(
                        "逐个计分",
                        TeachingMove.DO,
                        "每个符合条件的标记获得 4 分。",
                        List.of(evidenceId))));
        TeachingPlan plan = new TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "计分教学",
                "学会按规则计算得分。",
                List.of(new TeachingPlan.PlannedSection(
                        1,
                        "scoring",
                        "计算得分",
                        "准确计算每个标记的得分。",
                        true,
                        true,
                        List.of("matching marker score"),
                        List.of("scoring"))),
                "player",
                Instant.now());
        var invocations = new ImmediateAuditedAgentInvocations();
        TeachingSectionDraftComposer composer = new TeachingSectionDraftComposer(
                request -> draft,
                new PolicyEvidenceVerifier(),
                invocations,
                VisualRulebookPageFacts.empty());
        TeachingSectionDraftCandidate candidate = composer.compose(
                plan,
                plan.sections().getFirst(),
                List.of(),
                List.of(evidence),
                runId,
                0,
                false);
        return new Fixture(plan, candidate, runId, invocations, composer);
    }

    private static RuleEvidence evidence(
            UUID evidenceId, UUID versionId, String heading, String excerpt, int page) {
        return new RuleEvidence(
                evidenceId,
                versionId,
                "SCORING",
                heading,
                excerpt,
                page,
                page);
    }

    private static SectionDraft draft(String title, String text, UUID evidenceId) {
        return new SectionDraft(
                title,
                VisualKind.SCOREBOARD,
                text,
                List.of(evidenceId),
                List.of(new StepDraft(
                        "逐项核对",
                        TeachingMove.DO,
                        text,
                        List.of(evidenceId))));
    }

    private static TeachingPlan.PlannedSection planned(int position, String topicKey, String title) {
        return new TeachingPlan.PlannedSection(
                position,
                topicKey,
                title,
                "准确核对这一项计分关系。",
                true,
                true,
                List.of(topicKey),
                List.of("scoring"));
    }

    private static LessonSection withVisual(LessonSection section, VisualFocus focus) {
        var step = section.steps().getFirst().withVisualFoci(TeachingMove.VISUAL, List.of(focus));
        return new LessonSection(
                section.position(),
                section.topicKey(),
                section.coverageTags(),
                section.title(),
                section.required(),
                section.evidenceStatus(),
                section.visualKind(),
                section.visualCaption(),
                List.of(focus.pageNumber()),
                section.visualSourceChunkIds(),
                List.of(step));
    }

    private record Fixture(
            TeachingPlan plan,
            TeachingSectionDraftCandidate candidate,
            UUID runId,
            ImmediateAuditedAgentInvocations invocations,
            TeachingSectionDraftComposer composer) {}
}
