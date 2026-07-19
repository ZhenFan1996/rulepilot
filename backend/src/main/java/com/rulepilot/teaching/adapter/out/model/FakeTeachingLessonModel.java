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
        List<StepDraft> steps = request.evidence().stream()
                .limit(request.maxSteps())
                .map(source -> new StepDraft(source.excerpt(), List.of(source.chunkId())))
                .toList();
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
