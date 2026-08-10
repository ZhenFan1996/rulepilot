package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Maps selected evidence and stored visual observations into one bounded teaching-model request. */
final class TeachingSectionModelRequestFactory {

    private final VisualRulebookPageFacts visualFacts;

    TeachingSectionModelRequestFactory(VisualRulebookPageFacts visualFacts) {
        this.visualFacts = visualFacts;
    }

    TeachingLessonModel.SectionRequest create(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            boolean includeVisualEvidence,
            boolean modelSupportsVisualEvidence) {
        boolean requiresVisualGrounding = includeVisualEvidence
                || TeachingVisualEvidenceSelector.hasVisualPageEvidence(evidence);
        List<TeachingLessonModel.PageImageInput> pageImages = requiresVisualGrounding
                ? TeachingVisualEvidenceSelector.select(planned, evidence, modelSupportsVisualEvidence)
                : List.of();
        return new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                planned.coverageTags(),
                priorSections,
                modelEvidence(plan.documentVersionId(), evidence),
                pageImages,
                planned.retrievalQueries(),
                plan.createdBy(),
                chapterScope(plan, planned));
    }

    private List<EvidenceInput> modelEvidence(java.util.UUID documentVersionId, List<RuleEvidence> evidence) {
        Set<Integer> pages = evidence.stream()
                .filter(source -> source.pageFrom() == source.pageTo())
                .map(RuleEvidence::pageFrom)
                .collect(Collectors.toSet());
        Map<Integer, String> factsByPage = visualFacts.find(documentVersionId, pages).stream()
                .collect(Collectors.toMap(
                        VisualRulebookPageFacts.PageFact::pageNumber,
                        VisualRulebookPageFacts.PageFact::presentationEvidenceText));
        return evidence.stream().map(source -> toModelEvidence(source, factsByPage)).toList();
    }

    private EvidenceInput toModelEvidence(RuleEvidence evidence, Map<Integer, String> factsByPage) {
        String visualFact = evidence.pageFrom() == evidence.pageTo() ? factsByPage.get(evidence.pageFrom()) : null;
        String excerpt = visualFact == null || VisualRulebookPageFacts.PageFact.isTranscribedRuleEvidence(evidence.excerpt())
                ? evidence.excerpt()
                : evidence.excerpt() + "\n\n" + visualFact;
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                excerpt,
                evidence.pageFrom(),
                evidence.pageTo());
    }

    private static String chapterScope(TeachingPlan plan, TeachingPlan.PlannedSection current) {
        String chapters = plan.sections().stream()
                .map(section -> (section.position() == current.position() ? "【当前章节】" : "")
                        + "第" + section.position() + "章《" + section.title() + "》："
                        + boundedChapterObjective(section.objective()))
                .collect(Collectors.joining("\n"));
        String scope = "完整章节分工（仅界定讲解边界，不是规则事实）：\n" + chapters
                + "\n当前章节只完整讲解自己的目标。其他章节已经明确负责的机制，只保留本章理解所必需的"
                + "阶段名、顺序、即时选择或结果；不要复述它们的触发、数量、成本、例外、计算、完整流程或图例映射。";
        return scope.length() <= 4_000 ? scope : scope.substring(0, 3_999) + "…";
    }

    private static String boundedChapterObjective(String objective) {
        String value = objective == null ? "" : objective.strip();
        return value.length() <= 280 ? value : value.substring(0, 279) + "…";
    }
}
