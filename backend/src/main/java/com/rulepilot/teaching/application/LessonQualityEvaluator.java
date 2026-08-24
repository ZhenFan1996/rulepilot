package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.LessonQualityReport;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.CheckType;
import com.rulepilot.teaching.domain.LessonQualityReport.OverallStatus;
import com.rulepilot.teaching.domain.LessonQualityReport.QualityCheck;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LessonQualityEvaluator {

    public LessonQualityReport evaluate(TeachingPlan plan, IllustratedLesson lesson) {
        List<QualityCheck> checks = new ArrayList<>();
        checks.add(requiredCoverage(plan, lesson));
        if (plan.sections().stream().anyMatch(section -> section.coverageTags().contains("source_coverage")
                || section.coverageTags().contains(TeachingSourceCoverageContract.CONTRACT_VERSION_TAG))) {
            checks.add(sourceRuleGroupCoverage(plan, lesson));
        }
        if (plan.sections().stream().anyMatch(section -> section.coverageTags().contains("source_dependency"))) {
            checks.add(sourceAvailability(plan));
        }
        checks.add(citationSupport(lesson));
        checks.add(new QualityCheck(
                CheckType.EXPANSION_SCOPE,
                CheckStatus.NOT_EVALUATED,
                "扩展范围尚未验证",
                "当前教学计划未保存扩展集合快照，不能确认讲解是否包含或排除了扩展规则。"));

        long passed = checks.stream().filter(check -> check.status() == CheckStatus.PASS).count();
        int score = (int) Math.round(passed * 100.0 / checks.size());
        OverallStatus status = checks.stream().anyMatch(check -> check.status() == CheckStatus.FAIL)
                ? OverallStatus.BLOCKED
                : checks.stream().anyMatch(check -> check.status() == CheckStatus.NOT_EVALUATED)
                        ? OverallStatus.NEEDS_REVIEW
                        : OverallStatus.READY;
        return new LessonQualityReport(status, score, checks);
    }

    private QualityCheck requiredCoverage(TeachingPlan plan, IllustratedLesson lesson) {
        long required = plan.sections().stream().filter(TeachingPlan.PlannedSection::required).count();
        long available = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(this::hasCitedEvidence)
                .count();
        long validated = lesson.sections().stream()
                .filter(LessonSection::required)
                .filter(section -> section.evidenceStatus() == EvidenceStatus.SUPPORTED)
                .count();
        CheckStatus status = available < required
                ? CheckStatus.FAIL
                : validated < required ? CheckStatus.NOT_EVALUATED : CheckStatus.PASS;
        return new QualityCheck(
                CheckType.REQUIRED_SECTION_COVERAGE,
                status,
                "必需章节已完成发布校验 " + validated + " / " + required,
                available < required
                        ? "有 " + (required - available) + " 个必需章节缺少可引用的规则证据。"
                        : validated < required
                                ? available + " 个必需章节已有引用，但还有 " + (required - validated)
                                        + " 个未完成引用归属、规则书版本与结构校验。"
                                : "所有必需章节都有规则证据，并已完成引用归属、规则书版本与结构校验。");
    }

    private QualityCheck citationSupport(IllustratedLesson lesson) {
        var supportedSteps = lesson.sections().stream()
                .filter(this::hasCitedEvidence)
                .flatMap(section -> section.steps().stream())
                .toList();
        long cited = supportedSteps.stream().filter(step -> !step.sourcePages().isEmpty()).count();
        int percentage = supportedSteps.isEmpty()
                ? 0
                : (int) Math.round(cited * 100.0 / supportedSteps.size());
        return new QualityCheck(
                CheckType.CITATION_SUPPORT,
                !supportedSteps.isEmpty() && percentage >= 95 ? CheckStatus.PASS : CheckStatus.FAIL,
                "规则步骤引用支持率 " + percentage + "%",
                cited + " / " + supportedSteps.size() + " 个有规则内容的步骤带来源页码；目标为至少 95%。");
    }

    private QualityCheck sourceRuleGroupCoverage(TeachingPlan plan, IllustratedLesson lesson) {
        boolean sourceContract = plan.sections().stream()
                .anyMatch(section -> section.coverageTags()
                        .contains(TeachingSourceCoverageContract.CONTRACT_VERSION_TAG));
        List<TeachingPlan.PlannedSection> sourceSections = plan.sections().stream()
                .filter(section -> sourceContract
                        ? ownsContractSlot(section)
                        : section.coverageTags().contains("source_coverage"))
                .toList();
        long required = sourceSections.size();
        long available = sourceSections.stream()
                .filter(planned -> coveredSourceOwner(planned, lesson, false))
                .count();
        long validated = sourceSections.stream()
                .filter(planned -> coveredSourceOwner(planned, lesson, true))
                .count();
        boolean sourceInventoryUnavailable = sourceContract && plan.sections().stream()
                .anyMatch(section -> section.coverageTags().contains(
                                TeachingSourceCoverageContract.INCOMPLETE_INVENTORY_TAG)
                        || section.coverageTags().contains(TeachingSourceCoverageContract.UNSOURCED_TAG));
        boolean sourcePageCatalogPartial = plan.sections().stream()
                .flatMap(section -> section.coverageTags().stream())
                .anyMatch(TeachingSourceCoverageContract.PARTIAL_SOURCE_PAGE_CATALOG_TAG::equals);
        CheckStatus status = sourceInventoryUnavailable
                ? CheckStatus.FAIL
                : sourcePageCatalogPartial
                ? CheckStatus.NOT_EVALUATED
                : available < required
                ? CheckStatus.FAIL
                : validated < required ? CheckStatus.NOT_EVALUATED : CheckStatus.PASS;
        return new QualityCheck(
                CheckType.SOURCE_RULE_GROUP_COVERAGE,
                status,
                "来源归属章节已完成发布校验 " + validated + " / " + required,
                sourceInventoryUnavailable
                        ? "来源义务清单仍不完整，或至少一个由规划 Agent 识别的必要教学单元没有可用来源；"
                                + "即使已有章节带引用，也不能把整局标为完整。"
                        : sourcePageCatalogPartial
                        ? "所有已读取页面的来源义务都已归属，但规则书仍有页面未获得可验证的视觉证据；"
                                + "当前讲解可阅读，但不能声称覆盖了整本规则书。"
                        : available < required
                        ? "有 " + (required - available) + " 个来源归属章节没有引用其规划时绑定的规则页。"
                        : validated < required
                                ? available + " 个来源归属章节已有绑定页引用，但还有 " + (required - validated)
                                        + " 个未完成发布校验。"
                                : "每个来源归属章节都引用了规划时绑定的规则页，并完成引用归属、版本与结构校验。");
    }

    private boolean ownsContractSlot(TeachingPlan.PlannedSection section) {
        return java.util.Arrays.stream(com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole.values())
                .map(TeachingSourceCoverageContract::roleTag)
                .anyMatch(section.coverageTags()::contains);
    }

    private boolean coveredSourceOwner(
            TeachingPlan.PlannedSection planned,
            IllustratedLesson lesson,
            boolean requireReview) {
        LessonSection section = lesson.sections().stream()
                .filter(candidate -> candidate.topicKey().equals(planned.topicKey()))
                .findFirst()
                .orElse(null);
        if (section == null
                || !hasCitedEvidence(section)
                || (requireReview && section.evidenceStatus() != EvidenceStatus.SUPPORTED)) {
            return false;
        }
        boolean citesOwnerPage = planned.sourcePageNumbers().isEmpty()
                || section.steps().stream()
                        .flatMap(step -> step.sourcePages().stream())
                        .anyMatch(planned.sourcePageNumbers()::contains);
        return citesOwnerPage;
    }

    private QualityCheck sourceAvailability(TeachingPlan plan) {
        List<TeachingPlan.PlannedSection> dependencies = plan.sections().stream()
                .filter(section -> section.coverageTags().contains("source_dependency"))
                .toList();
        List<String> sources = dependencies.stream()
                .flatMap(section -> TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).stream()
                        .map(title -> sourceReference(section.sourcePageNumbers(), title)))
                .distinct()
                .toList();
        long sourceCount = dependencies.stream()
                .flatMap(section -> TeachingUnitContract.sourceIdentifiers(section.retrievalQueries()).stream())
                .map(this::normalized)
                .distinct()
                .count();
        Set<String> missingResponsibilities = new LinkedHashSet<>();
        dependencies.stream()
                .flatMap(section -> section.coverageTags().stream())
                .filter(tag -> tag.startsWith("missing_") && tag.endsWith("_source"))
                .map(tag -> tag.substring("missing_".length(), tag.length() - "_source".length()))
                .map(this::responsibilityLabel)
                .forEach(missingResponsibilities::add);
        String missingDetail = missingResponsibilities.isEmpty()
                ? "当前文档不包含这些被引用资料中的具体规则。"
                : "当前文档不包含" + String.join("、", missingResponsibilities) + "。";
        return new QualityCheck(
                CheckType.SOURCE_AVAILABILITY,
                CheckStatus.FAIL,
                "当前规则书还缺 " + sourceCount + " 份被明确引用的资料",
                String.join("；", sources) + "；这些名称只证明来源依赖存在，" + missingDetail
                        + "需要合法补充相应资料后才能核对并补齐讲解。");
    }

    private String sourceReference(List<Integer> pages, String title) {
        String pageLabel = pages.isEmpty()
                ? "来源页"
                : "第 " + pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、")) + " 页";
        return pageLabel + "指向 " + title;
    }

    private String responsibilityLabel(String tag) {
        return switch (tag) {
            case "setup" -> "开局步骤";
            case "core_loop" -> "核心回合流程";
            case "end" -> "结束规则";
            case "scoring" -> "计分规则";
            default -> "对应规则";
        };
    }

    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private boolean hasCitedEvidence(LessonSection section) {
        return section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE;
    }

}
