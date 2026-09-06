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
            List<RuleEvidence> evidence) {
        return new TeachingLessonModel.SectionRequest(
                planned.topicKey(),
                planned.title(),
                planned.objective(),
                priorSections,
                evidence.stream().map(this::toModelEvidence).toList(),
                plan.createdBy());
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
