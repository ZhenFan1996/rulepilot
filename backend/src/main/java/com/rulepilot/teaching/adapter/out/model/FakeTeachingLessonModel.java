package com.rulepilot.teaching.adapter.out.model;

import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeTeachingLessonModel implements TeachingLessonModel {

    @Override
    public String providerId() {
        return "fake";
    }

    @Override
    public SectionDraft compose(SectionRequest request) {
        List<StepDraft> steps = request.teachingUnits().isEmpty()
                ? request.evidence().stream()
                        .map(source -> new StepDraft(source.excerpt(), List.of(source.chunkId())))
                        .toList()
                : request.teachingUnits().stream().map(unit -> {
                    List<java.util.UUID> citations = request.evidence().stream()
                            .filter(source -> unit.sourceIdentifiers().stream()
                                    .anyMatch(identifier -> source.excerpt().contains(identifier)))
                            .map(EvidenceInput::chunkId)
                            .distinct()
                            .toList();
                    if (citations.isEmpty()) citations = List.of(request.evidence().getFirst().chunkId());
                    String text = String.join("；", unit.sourceIdentifiers());
                    return new StepDraft(
                            "按来源完成本单元",
                            com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove.DO,
                            text,
                            citations,
                            List.of(unit.unitId()),
                            null);
                }).toList();
        return new SectionDraft(
                request.title(),
                visual(request.coverageTags()),
                "按引用证据逐步完成本节操作",
                List.of(request.evidence().getFirst().chunkId()),
                steps);
    }

    private VisualKind visual(List<String> tags) {
        if (tags.contains("setup") || tags.contains("components")) return VisualKind.TABLE_LAYOUT;
        if (tags.contains("scoring") || tags.contains("tie_breaker")) return VisualKind.SCOREBOARD;
        if (tags.contains("core_loop") || tags.contains("actions")) return VisualKind.FLOW_DIAGRAM;
        return VisualKind.REFERENCE_CARD;
    }
}
