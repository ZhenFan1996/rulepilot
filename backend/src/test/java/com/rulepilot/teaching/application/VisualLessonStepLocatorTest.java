package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.BlockRole;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionProposer;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualSourceKind;
import com.rulepilot.visualaid.VisualRegionCatalog;
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
    void offersOnlyPlannedVisualPagesBeyondTheRuleCitationAndSkipsAlreadyUsedRegions() {
        UUID documentVersionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        DocumentPageImages pageImages = mock(DocumentPageImages.class);
        VisualRegionLocator locator = mock(VisualRegionLocator.class);
        when(pageImages.read(documentVersionId, Set.of(6)))
                .thenReturn(List.of(new DocumentPageImages.PageImage(
                        6, "image/png", new byte[] {1}, 1_000, 1_000)));
        when(locator.locateGuideWithResult(any(), any(Duration.class))).thenAnswer(invocation -> {
            VisualRegionLocator.VisualLocationRequest request = invocation.getArgument(0);
            assertThat(request.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.pageNumber()).isEqualTo(6);
                assertThat(candidate.rectangle()).isEqualTo(new Rectangle(220, 240, 500, 320));
            });
            var candidate = request.candidates().getFirst();
            return VisualRegionLocator.LocateGuideResult.found(List.of(new VisualRegionLocator.LocatedRegion(
                    candidate.pageNumber(),
                    "worked example",
                    "A worked example is visible.",
                    candidate.rectangle().x(),
                    candidate.rectangle().y(),
                    candidate.rectangle().width(),
                    candidate.rectangle().height(),
                    List.of(evidenceId),
                    List.of(1),
                    false,
                    candidate.sourceKind())));
        });
        VisualRegionCatalog catalog = (versionId, pageNumbers) -> List.of(
                new VisualRegionCatalog.Region(5, "PICTURE", 100, 120, 420, 300),
                new VisualRegionCatalog.Region(6, "PICTURE", 220, 240, 500, 320));
        RulebookUnderstanding understanding = new RulebookUnderstanding(
                List.of(
                        pageBlock(2, 0),
                        pageBlock(5, 1),
                        pageBlock(6, 2)),
                List.of(),
                List.of(),
                List.of());
        LessonStep step = step(evidenceId, 2);
        LessonSection section = section(evidenceId, step);
        var stepLocator = new VisualLessonStepLocator(
                pageImages,
                new VisualRegionCandidateSelector(),
                VisualRegionProposer.unavailable(),
                catalog,
                locator,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(5));
        VisualFocus alreadyUsed = new VisualFocus(
                5, "earlier figure", "An earlier figure.", 100, 120, 420, 300);

        VisualLessonStepLocator.Result result = stepLocator.locate(
                understanding,
                documentVersionId,
                section,
                List.of(step),
                "player",
                null,
                Instant.now().plus(Duration.ofMinutes(5)),
                stepLocator.beginProposalWorkflow(),
                List.of(alreadyUsed),
                List.of(5, 6));

        assertThat(result.rejection()).isNull();
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.pageNumber()).isEqualTo(6);
            assertThat(region.supportedEvidenceIds()).containsExactly(evidenceId);
        });
    }

    @Test
    void offersPersistedLayoutCandidatesWhenTheLocalPixelToolIsUnavailable() {
        UUID documentVersionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        DocumentPageImages pageImages = mock(DocumentPageImages.class);
        VisualRegionProposer localProposals = mock(VisualRegionProposer.class);
        VisualRegionLocator locator = mock(VisualRegionLocator.class);
        when(localProposals.configured()).thenReturn(true);
        when(pageImages.read(documentVersionId, Set.of(2)))
                .thenReturn(List.of(new DocumentPageImages.PageImage(
                        2, "image/png", new byte[] {1}, 1_000, 1_000)));
        when(locator.locateGuideWithResult(any(), any(Duration.class))).thenAnswer(invocation -> {
            VisualRegionLocator.VisualLocationRequest request = invocation.getArgument(0);
            assertThat(request.candidates()).hasSize(1);
            var candidate = request.candidates().getFirst();
            return VisualRegionLocator.LocateGuideResult.found(List.of(new VisualRegionLocator.LocatedRegion(
                    candidate.pageNumber(),
                    "setup diagram",
                    "A bounded setup diagram is visible.",
                    candidate.rectangle().x(),
                    candidate.rectangle().y(),
                    candidate.rectangle().width(),
                    candidate.rectangle().height(),
                    List.of(evidenceId),
                    List.of(1),
                    false,
                    candidate.sourceKind())));
        });
        VisualRegionCatalog catalog = (versionId, pageNumbers) -> List.of(
                new VisualRegionCatalog.Region(2, "TABLE", 40, 80, 800, 500),
                new VisualRegionCatalog.Region(2, "PICTURE", 120, 180, 420, 360));
        LessonStep step = step(evidenceId, 2);
        LessonSection section = section(evidenceId, step);
        var stepLocator = new VisualLessonStepLocator(
                pageImages,
                new VisualRegionCandidateSelector(),
                localProposals,
                catalog,
                locator,
                new VisualReaderCropPolicy(),
                null,
                Clock.systemUTC(),
                Duration.ofMinutes(5));

        VisualLessonStepLocator.Result result = stepLocator.locate(
                mock(RulebookUnderstanding.class),
                documentVersionId,
                section,
                List.of(step),
                "player");

        assertThat(result.rejection()).isNull();
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.pageNumber()).isEqualTo(2);
            assertThat(new Rectangle(region.x(), region.y(), region.width(), region.height()))
                    .isEqualTo(new Rectangle(120, 180, 420, 360));
        });
        verify(localProposals, never()).propose(any(), any(Duration.class));
    }

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

    private LessonStep step(UUID evidenceId, int pageNumber) {
        return new LessonStep(
                1,
                "Apply the cited rule",
                TeachingMove.DO,
                "Follow the rule shown on the cited page.",
                List.of(pageNumber),
                List.of(evidenceId),
                List.of(),
                null,
                List.of());
    }

    private LessonSection section(UUID evidenceId, LessonStep step) {
        return new LessonSection(
                1,
                "rule",
                List.of(),
                "Rule",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "Rule",
                step.sourcePages(),
                List.of(evidenceId),
                List.of(step));
    }

    private RulebookUnderstanding.PageBlock pageBlock(int pageNumber, int blockIndex) {
        return new RulebookUnderstanding.PageBlock(
                pageNumber,
                blockIndex,
                blockIndex,
                BlockRole.BODY,
                "Rule text on page " + pageNumber,
                new Rectangle(0, 0, 1_000, 100),
                null);
    }
}
