package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.shared.PlayerLocale;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.visualaid.VisualRegionCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonEnricherTest {
    @Test void laterPageFailureRetainsEarlierSourceImagesButCancellationPropagates() {
        UUID version = UUID.randomUUID();
        var section = new TeachingPlan.PlannedSection(1, "setup", "Setup", "Prepare the table", true, true,
                List.of("setup"), List.of(), List.of(1, 2, 3, 4, 5, 6), List.of(1));
        var plan = new TeachingPlan(UUID.randomUUID(), version, "Game", "Premise", List.of(section), "player", Instant.now(), PlayerLocale.EN);
        var pages = mock(DocumentPageImages.class);
        var catalog = mock(VisualRegionCatalog.class);
        var locator = mock(VisualRegionLocator.class);
        var control = mock(AgentExecutionControl.class);
        var focus = new VisualFocus(1, "Complete component", "A complete board component", 100, 100, 200, 300);
        when(pages.read(version, Set.of(1, 2, 3, 4, 5))).thenReturn(List.of(new DocumentPageImages.PageImage(1, "image/png", new byte[]{1}, 100, 100)));
        when(pages.read(version, Set.of(6))).thenThrow(new IllegalStateException("object store unavailable"));
        when(catalog.find(eq(version), any())).thenReturn(List.of(new VisualRegionCatalog.Region(1, "PDF_IMAGE", 100, 100, 200, 300)));
        when(locator.observe(any(), any())).thenReturn(List.of(focus));
        var enricher = new VisualLessonEnricher(pages, catalog, locator, control, Duration.ofMinutes(1), Duration.ofSeconds(10));
        var evidence = List.of(new RuleEvidence(UUID.randomUUID(), version, "RULE", "setup", "Source rule", 1, 1));
        assertThat(enricher.prepare(plan, section, evidence, null)).singleElement()
                .satisfies(image -> assertThat(image.focus()).isEqualTo(focus));
        doThrow(new AgentExecutionStoppedException(AgentExecutionStoppedException.StopReason.CANCELLED)).when(pages).read(version, Set.of(6));
        assertThatThrownBy(() -> enricher.prepare(plan, section, evidence, null)).isInstanceOf(AgentExecutionStoppedException.class);
    }
}
