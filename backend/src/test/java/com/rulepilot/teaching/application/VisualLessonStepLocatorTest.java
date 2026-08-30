package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonStepLocatorTest {

    @Test
    void observationalTeachingTokensDoNotPreventOptionalVisualWork() {
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        DocumentPageImages pageImages = mock(DocumentPageImages.class);
        VisualRegionCandidateSelector candidates = mock(VisualRegionCandidateSelector.class);
        VisualRegionLocator locator = mock(VisualRegionLocator.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        var candidate = new VisualRegionCandidateSelector.Candidate(
                "candidate_1",
                1,
                new Rectangle(0, 0, 550, 550),
                VisualSourceKind.PAGE_REGION);
        when(candidates.select(any(), any(), any(), any())).thenReturn(List.of(candidate));
        when(pageImages.read(documentVersionId, Set.of(1)))
                .thenReturn(List.of(new DocumentPageImages.PageImage(
                        1, "image/png", new byte[] {1}, 1_000, 1_000)));
        when(locator.locateGuideWithResult(any(), any(Duration.class)))
                .thenReturn(VisualRegionLocator.LocateGuideResult.unavailable(
                        VisualRegionLocator.Diagnostic.EXPLICIT_NO_REGION));
        when(execution.budget(runId)).thenReturn(new AgentExecutionControl.BudgetSnapshot(
                10,
                0,
                0,
                11,
                now.plus(Duration.ofMinutes(5)),
                null,
                false));
        LessonStep step = new LessonStep(
                1,
                "Apply the cited rule",
                TeachingMove.DO,
                "Follow the rule shown on the cited page.",
                List.of(1),
                List.of(evidenceId),
                List.of(),
                null,
                List.of());
        LessonSection section = new LessonSection(
                1,
                "rule",
                List.of(),
                "Rule",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "Rule",
                List.of(1),
                List.of(evidenceId),
                List.of(step));
        var stepLocator = new VisualLessonStepLocator(
                pageImages,
                candidates,
                locator,
                new VisualReaderCropPolicy(),
                execution,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(5));

        VisualLessonStepLocator.Result result = stepLocator.locate(
                mock(RulebookUnderstanding.class),
                documentVersionId,
                section,
                List.of(step),
                "player",
                runId,
                now.plus(Duration.ofMinutes(5)));

        assertThat(result.regions()).isEmpty();
        assertThat(result.rejection()).isEqualTo(VisualLessonEnricher.Outcome.MODEL_EXPLICIT_NO_REGION);
        verify(locator).locateGuideWithResult(any(), any(Duration.class));
    }
}
