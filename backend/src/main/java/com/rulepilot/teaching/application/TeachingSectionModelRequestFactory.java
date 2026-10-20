package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PriorSectionContext;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;

/** Maps citation-owned rule evidence into the teaching-model request; visual selection has its own owner. */
final class TeachingSectionModelRequestFactory {

    TeachingLessonModel.SectionRequest create(
            TeachingPlan plan,
            TeachingPlan.PlannedSection planned,
            List<PriorSectionContext> priorSections,
            List<RuleEvidence> evidence,
            List<TeachingLessonModel.VisualInput> visuals) {
        return new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                priorSections,
                evidence.stream().map(this::toModelEvidence).toList(),
                sourceVisuals(plan, evidence, visuals), plan.createdBy(), plan.outputLanguage());
    }

    /** Original pages remain selectable when optional crop interpretation is unavailable. */
    private List<TeachingLessonModel.VisualInput> sourceVisuals(
            TeachingPlan plan, List<RuleEvidence> evidence, List<TeachingLessonModel.VisualInput> observed) {
        var visuals = new java.util.LinkedHashMap<String, TeachingLessonModel.VisualInput>();
        observed.forEach(visual -> visuals.put(visual.imageId(), visual));
        var pages = new java.util.LinkedHashMap<Integer, java.util.List<java.util.UUID>>();
        evidence.stream().filter(source -> plan.documentVersionId().equals(source.documentVersionId()))
                .forEach(source -> java.util.stream.IntStream.rangeClosed(source.pageFrom(), source.pageTo())
                        .forEach(page -> pages.computeIfAbsent(page, ignored -> new java.util.ArrayList<>()).add(source.chunkId())));
        pages.forEach((page, ids) -> {
            var focus = new com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus(page,
                    "Source page", "Original source page for direct inspection; graphical relationships are not interpreted rule evidence.",
                    0, 0, 1_000, 1_000, com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind.FULL_PAGE);
            String id = focus.imageId(plan.documentVersionId());
            visuals.putIfAbsent(id, new TeachingLessonModel.VisualInput(id, focus, ids.stream().distinct().toList()));
        });
        return List.copyOf(visuals.values());
    }

    private EvidenceInput toModelEvidence(RuleEvidence evidence) {
        return new EvidenceInput(
                evidence.chunkId(),
                evidence.sectionType(),
                evidence.heading(),
                evidence.excerpt(),
                switch (evidence.contentKind()) {
                    case CANONICAL_TEXT -> TeachingLessonModel.EvidenceContentKind.CANONICAL_TEXT;
                    case VISUAL_PLACEHOLDER -> TeachingLessonModel.EvidenceContentKind.VISUAL_PLACEHOLDER;
                    case CANONICAL_TEXT_WITH_VISUAL_FACTS -> TeachingLessonModel.EvidenceContentKind.CANONICAL_TEXT_WITH_VISUAL_FACTS;
                    case VISUAL_TRANSCRIPTION -> TeachingLessonModel.EvidenceContentKind.VISUAL_TRANSCRIPTION;
                },
                evidence.pageFrom(),
                evidence.pageTo());
    }

}
