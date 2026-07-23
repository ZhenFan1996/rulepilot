package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;

/** Source-cited, normalized section draft retained for bounded post-publication review. */
record TeachingSectionDraftCandidate(
        int sectionIndex,
        TeachingPlan.PlannedSection planned,
        List<RuleEvidence> evidence,
        TeachingLessonModel.SectionRequest modelRequest,
        SectionDraft draft,
        LessonSection section) {}
