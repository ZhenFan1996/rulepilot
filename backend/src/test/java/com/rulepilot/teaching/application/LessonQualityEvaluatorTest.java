package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentExecutionStoppedException.StopReason;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.application.PolicyEvidenceVerifier;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.TopicDraft;
import com.rulepilot.teaching.adapter.out.model.FakeTeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LessonQualityEvaluatorTest {

    @Test
    void treatsACompleteCitedDraftAsReadableAndPendingReviewInsteadOfBlocked() {
        UUID versionId = UUID.randomUUID();
        var outline = new OutlineDraft("SETI", "Search for life.", List.of(
                topic("table", "Build the solar system", List.of("setup"), "setup"),
                topic("turn", "Launch and scan", List.of("core_loop"), "turn"),
                topic("finish", "End round five and score", List.of("end", "scoring"), "scoring")));
        var plan = new TeachingPlanFactory().create(versionId, "player", outline);
        List<LessonSection> citedSections = plan.sections().stream()
                .map(section -> new LessonSection(
                        section.position(),
                        section.topicKey(),
                        section.coverageTags(),
                        section.title(),
                        section.required(),
                        EvidenceStatus.CITED_DRAFT,
                        VisualKind.REFERENCE_CARD,
                        "带引用的待核对提示。",
                        List.of(new LessonStep(
                                1,
                                "照着做",
                                TeachingMove.DO,
                                "执行当前章节的有引用步骤。",
                                List.of(2),
                                List.of(UUID.randomUUID())))))
                .toList();
        var lesson = new IllustratedLesson(
                UUID.randomUUID(),
                plan.id(),
                LessonStatus.DRAFT_READY,
                citedSections,
                "test",
                Instant.now());

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.NEEDS_REVIEW);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.REQUIRED_SECTION_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.NOT_EVALUATED);
                    assertThat(check.summary()).contains("已核对 0 / 3");
                    assertThat(check.detail())
                            .contains("不能确认整套讲解可以独立开桌")
                            .doesNotContain("完整基础讲解已经可读");
                });
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.CITATION_SUPPORT)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.PASS);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.SETUP_EXECUTABILITY
                        || check.type() == CheckType.END_AND_SCORING_COMPLETENESS)
                .allSatisfy(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.NOT_EVALUATED);
                    assertThat(check.summary()).contains("待核对");
                    assertThat(check.detail())
                            .contains("不能确认")
                            .doesNotContain("可以使用");
                });
    }

    @Test
    void usesTheDocumentGeneratedObjectiveWhenOnePlannedQueryMisses() {
        UUID versionId = UUID.randomUUID();
        var outline = new OutlineDraft("SETI", "Search for life.", List.of(
                topic("table", "Build the solar system", List.of("setup"), "setup"),
                topic("turn", "Launch and scan", List.of("core_loop"), "turn"),
                topic("finish", "End round five and score", List.of("end", "scoring"), "scoring")));
        var plan = new TeachingPlanFactory().create(versionId, "player", outline);
        var lesson = new GroundedTeachingAgent(
                        request -> "turn".equals(request.query())
                                ? List.of()
                                : List.of(new RuleEvidence(
                                        UUID.randomUUID(), versionId, "GENERAL", request.query(),
                                        "A directly supported rule for " + request.query(), 2, 2)),
                        new FakeTeachingLessonModel(),
                        new PolicyEvidenceVerifier(),
                        acceptedCritic(),
                        new ImmediateAuditedAgentInvocations(),
                        24)
                .create(plan, UUID.randomUUID());

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.NEEDS_REVIEW);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.CITATION_SUPPORT)
                .extracting(check -> check.status())
                .containsExactly(CheckStatus.PASS);
    }

    @Test
    void reportsTheValidatedPlanUnitReceiptSeparatelyFromChapterCountAndReviewState() {
        UUID versionId = UUID.randomUUID();
        List<String> ruleGroups = List.of(
                "turn start", "ordinary action", "alternative action", "movement",
                "building", "resource limit", "turn end", "exception");
        var plan = new com.rulepilot.teaching.domain.TeachingPlan(
                UUID.randomUUID(),
                versionId,
                "Game",
                "Premise",
                List.of(new com.rulepilot.teaching.domain.TeachingPlan.PlannedSection(
                        1,
                        "source-page",
                        "玩法页",
                        "Teach every visible rule group.",
                        true,
                        true,
                        ruleGroups,
                        List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                        List.of(1))),
                "player",
                Instant.now());
        LessonSection citedDraft = new LessonSection(
                1,
                "source-page",
                plan.sections().getFirst().coverageTags(),
                "玩法页",
                true,
                EvidenceStatus.CITED_DRAFT,
                VisualKind.REFERENCE_CARD,
                "待核对。",
                List.of(new LessonStep(
                        1, "照着做", TeachingMove.DO, "执行有引用的玩法步骤。", List.of(1), List.of(UUID.randomUUID()))));
        var pendingLesson = new IllustratedLesson(
                UUID.randomUUID(), plan.id(), LessonStatus.DRAFT_READY, List.of(citedDraft), "test", Instant.now());

        var pending = new LessonQualityEvaluator().evaluate(plan, pendingLesson);

        assertThat(pending.checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.NOT_EVALUATED);
                    assertThat(check.summary()).isEqualTo("来源规则组已核对 0 / 8");
                    assertThat(check.detail()).contains("已有引用", "未通过");
                });

        LessonSection supported = new LessonSection(
                citedDraft.position(),
                citedDraft.topicKey(),
                citedDraft.coverageTags(),
                citedDraft.title(),
                citedDraft.required(),
                EvidenceStatus.SUPPORTED,
                citedDraft.visualKind(),
                citedDraft.visualCaption(),
                citedDraft.steps());
        var supportedReceiptLesson = new IllustratedLesson(
                UUID.randomUUID(), plan.id(), LessonStatus.COMPLETE, List.of(supported), "test", Instant.now());

        assertThat(new LessonQualityEvaluator().evaluate(plan, supportedReceiptLesson).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.PASS);
                    assertThat(check.summary()).isEqualTo("来源规则组已核对 8 / 8");
                });

        List<LessonStep> individuallyBoundSteps = java.util.stream.IntStream.range(0, ruleGroups.size())
                .mapToObj(index -> new LessonStep(
                        index + 1,
                        ruleGroups.get(index),
                        TeachingMove.DO,
                        "讲解 " + ruleGroups.get(index) + " 的可执行规则。",
                        List.of(1),
                        List.of(UUID.randomUUID())))
                .toList();
        LessonSection individuallyBound = new LessonSection(
                supported.position(),
                supported.topicKey(),
                supported.coverageTags(),
                supported.title(),
                supported.required(),
                EvidenceStatus.SUPPORTED,
                supported.visualKind(),
                supported.visualCaption(),
                individuallyBoundSteps);
        var actuallyCoveredLesson = new IllustratedLesson(
                UUID.randomUUID(), plan.id(), LessonStatus.COMPLETE, List.of(individuallyBound), "test", Instant.now());

        assertThat(new LessonQualityEvaluator().evaluate(plan, actuallyCoveredLesson).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.PASS);
                    assertThat(check.summary()).isEqualTo("来源规则组已核对 8 / 8");
                });
    }

    @Test
    void blocksReadinessAndNamesAnExplicitlyReferencedButMissingSource() {
        UUID planId = UUID.randomUUID();
        var plan = new com.rulepilot.teaching.domain.TeachingPlan(
                planId,
                UUID.randomUUID(),
                "Game",
                "Premise",
                List.of(new com.rulepilot.teaching.domain.TeachingPlan.PlannedSection(
                        1,
                        "source-dependency-page-1",
                        "当前规则书还需要的资料",
                        "The source points elsewhere; do not teach the missing procedure.",
                        true,
                        true,
                        List.of("Quick Start Guide"),
                        List.of("source_dependency", "missing_setup_source"),
                        List.of(1))),
                "player",
                Instant.now());
        LessonSection dependencyNotice = new LessonSection(
                1,
                "source-dependency-page-1",
                plan.sections().getFirst().coverageTags(),
                "当前规则书还需要的资料",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "规则书第 1 页指向另一份资料。",
                List.of(new LessonStep(
                        1,
                        "缺少来源",
                        TeachingMove.WATCH,
                        "当前规则书要求另行查看 Quick Start Guide；这不等于已经提供开局步骤。",
                        List.of(1),
                        List.of(UUID.randomUUID()))));
        var lesson = new IllustratedLesson(
                UUID.randomUUID(), planId, LessonStatus.INCOMPLETE, List.of(dependencyNotice), "test", Instant.now());

        var report = new LessonQualityEvaluator().evaluate(plan, lesson);

        assertThat(report.status()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_AVAILABILITY)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.FAIL);
                    assertThat(check.summary()).isEqualTo("当前规则书还缺 1 份被明确引用的资料");
                    assertThat(check.detail()).contains("Quick Start Guide", "第 1 页", "不包含开局步骤");
                });
        assertThat(report.checks())
                .filteredOn(check -> check.type() == CheckType.SETUP_EXECUTABILITY)
                .singleElement()
                .extracting(check -> check.status())
                .isEqualTo(CheckStatus.FAIL);
    }

    @Test
    void aSupportedReceiptStillRequiresACitationToThePlannedOwnerPage() {
        UUID planId = UUID.randomUUID();
        var planned = new com.rulepilot.teaching.domain.TeachingPlan.PlannedSection(
                1,
                "source-page",
                "End",
                "Teach the visible End rule group.",
                true,
                false,
                List.of("End"),
                List.of("setup", "core_loop", "end", "scoring", "source_coverage"),
                List.of(2));
        var plan = new com.rulepilot.teaching.domain.TeachingPlan(
                planId,
                UUID.randomUUID(),
                "Game",
                "Premise",
                List.of(planned),
                "player",
                Instant.now());
        LessonSection falseMatch = new LessonSection(
                1,
                "source-page",
                planned.coverageTags(),
                "终局",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "核对终局。",
                List.of(new LessonStep(
                        1,
                        "Weekend marker",
                        TeachingMove.WATCH,
                        "Only inspect the weekend marker.",
                        List.of(9),
                        List.of(UUID.randomUUID()))));
        var lesson = new IllustratedLesson(
                UUID.randomUUID(), planId, LessonStatus.COMPLETE, List.of(falseMatch), "test", Instant.now());

        assertThat(new LessonQualityEvaluator().evaluate(plan, lesson).checks())
                .filteredOn(check -> check.type() == CheckType.SOURCE_RULE_GROUP_COVERAGE)
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo(CheckStatus.FAIL);
                    assertThat(check.summary()).isEqualTo("来源规则组已核对 0 / 1");
                });
    }

    private TopicDraft topic(String key, String title, List<String> tags, String query) {
        return new TopicDraft(key, title, "Explain " + title, true, false, List.of(query), tags);
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }
}
