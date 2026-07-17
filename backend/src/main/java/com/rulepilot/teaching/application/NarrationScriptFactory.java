package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.NarrationScript;
import com.rulepilot.teaching.domain.NarrationScript.NarrationChapter;
import com.rulepilot.teaching.domain.NarrationScript.NarrationSegment;
import com.rulepilot.teaching.domain.NarrationScript.ScriptStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NarrationScriptFactory {

    static final String INSUFFICIENT_EVIDENCE_MESSAGE = "本节暂无足够规则证据，已跳过。";

    public NarrationScript create(IllustratedLesson lesson) {
        var chapters = lesson.sections().stream().map(this::chapter).toList();
        var status = chapters.stream().allMatch(NarrationChapter::supported)
                ? ScriptStatus.READY
                : ScriptStatus.INCOMPLETE;
        var scriptId = UUID.nameUUIDFromBytes(
                ("narration:" + lesson.id()).getBytes(StandardCharsets.UTF_8));
        return new NarrationScript(scriptId, lesson.id(), status, chapters, lesson.createdAt());
    }

    private NarrationChapter chapter(IllustratedLesson.LessonSection section) {
        var supported = section.evidenceStatus() == EvidenceStatus.SUPPORTED;
        List<NarrationSegment> segments = supported
                ? section.steps().stream()
                        .map(step -> new NarrationSegment(step.position(), step.text(), step.sourcePages()))
                        .toList()
                : List.of(new NarrationSegment(1, INSUFFICIENT_EVIDENCE_MESSAGE, List.of()));
        return new NarrationChapter(
                section.position(), section.type(), section.title(), supported, segments);
    }
}
