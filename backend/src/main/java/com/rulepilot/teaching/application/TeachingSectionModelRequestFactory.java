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
        List<TeachingUnitContract.Unit> plannedUnits = TeachingUnitContract.decodeUnits(planned.retrievalQueries());
        return new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                planned.coverageTags(),
                priorSections,
                modelEvidence(plan.documentVersionId(), evidence),
                pageImages,
                plannedUnits.isEmpty()
                        ? planned.retrievalQueries()
                        : plannedUnits.stream()
                                .flatMap(unit -> unit.sourceIdentifiers().stream())
                                .distinct()
                                .toList(),
                plannedUnits.stream()
                        .map(unit -> boundTeachingUnit(unit, evidence))
                        .toList(),
                plan.createdBy(),
                chapterScope(plan, planned),
                wholeGameContext(plan));
    }

    private TeachingLessonModel.TeachingUnitInput boundTeachingUnit(
            TeachingUnitContract.Unit unit, List<RuleEvidence> evidence) {
        if (!unit.sourcePages().isEmpty()) {
            List<java.util.UUID> ownedEvidenceIds = evidence.stream()
                    .filter(candidate -> java.util.stream.IntStream
                            .rangeClosed(candidate.pageFrom(), candidate.pageTo())
                            .anyMatch(unit.sourcePages()::contains))
                    .map(RuleEvidence::chunkId)
                    .distinct()
                    .toList();
            if (ownedEvidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "retrieval did not bind planned teaching source pages " + unit.sourcePages());
            }
            return new TeachingLessonModel.TeachingUnitInput(
                    unit.unitId(), unit.sourceIdentifiers(), ownedEvidenceIds);
        }
        List<java.util.UUID> ownedEvidenceIds = evidence.stream()
                .map(RuleEvidence::chunkId)
                .distinct()
                .toList();
        return new TeachingLessonModel.TeachingUnitInput(
                unit.unitId(), unit.sourceIdentifiers(), ownedEvidenceIds);
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
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                evidence.excerpt(),
                evidence.contentKind() == RuleEvidence.ContentKind.VISUAL_TRANSCRIPTION ? null : visualFact,
                switch (evidence.contentKind()) {
                    case CANONICAL_TEXT -> TeachingLessonModel.EvidenceContentKind.CANONICAL_TEXT;
                    case VISUAL_PLACEHOLDER -> TeachingLessonModel.EvidenceContentKind.VISUAL_PLACEHOLDER;
                    case CANONICAL_TEXT_WITH_VISUAL_FACTS -> TeachingLessonModel.EvidenceContentKind.CANONICAL_TEXT_WITH_VISUAL_FACTS;
                    case VISUAL_TRANSCRIPTION -> TeachingLessonModel.EvidenceContentKind.VISUAL_TRANSCRIPTION;
                },
                evidence.pageFrom(),
                evidence.pageTo());
    }

    private static String chapterScope(TeachingPlan plan, TeachingPlan.PlannedSection current) {
        String chapters = plan.sections().stream()
                .map(section -> (section.position() == current.position() ? "【当前章节】" : "")
                        + "第" + section.position() + "章《" + section.title() + "》："
                        + section.objective())
                .collect(Collectors.joining("\n"));
        String scope = "完整章节分工（仅界定讲解边界，不是规则事实）：\n" + chapters
                + "\n当前章节只完整讲解自己的目标。其他章节已经明确负责的机制，只保留本章理解所必需的"
                + "阶段名、顺序、即时选择或结果；不要复述它们的触发、数量、成本、例外、计算、完整流程或图例映射。";
        return scope;
    }

    private static TeachingLessonModel.WholeGameContextInput wholeGameContext(TeachingPlan plan) {
        var context = plan.wholeGameContext();
        return new TeachingLessonModel.WholeGameContextInput(
                context.summary(),
                context.concepts().stream()
                        .map(concept -> new TeachingLessonModel.GlobalConceptInput(
                                concept.conceptId(),
                                concept.label(),
                                concept.explanation(),
                                concept.sourceIdentifiers(),
                                concept.sourcePageNumbers(),
                                concept.relatedTopicKeys(),
                                concept.prerequisiteConceptIds()))
                        .toList(),
                context.topicDependencies().stream()
                        .map(dependency -> new TeachingLessonModel.TopicDependencyInput(
                                dependency.prerequisiteTopicKey(),
                                dependency.dependentTopicKey(),
                                dependency.reason()))
                        .toList(),
                context.evidenceBound());
    }

}
