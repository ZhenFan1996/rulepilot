package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.VisualRulebookPageFacts;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentNativeVisualEvidenceTest {

    @Test
    void exposesOnlyCurrentSchemaFactsToTheNativeAnswerTool() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools reads = mock(AssistantReadTools.class);
        when(reads.readRuleEvidenceIds(versionId, Set.of(evidenceId))).thenReturn(List.of(new RuleEvidence(
                evidenceId, versionId, "PLAY", "Play", "Take one action.", 4, 4)));
        VisualRulebookPageFacts facts = mock(VisualRulebookPageFacts.class);
        when(facts.find(versionId, Set.of(4))).thenReturn(List.of(
                new PageFact(
                        4,
                        "old board label",
                        "An obsolete visual summary.",
                        List.of("old", "board"),
                        List.of(),
                        PageFact.CURRENT_SCHEMA_VERSION - 1),
                new PageFact(
                        4,
                        "current board label",
                        "A current but deliberately partial visual observation.",
                        List.of("current", "board"))));
        var adapter = new DocumentNativeVisualEvidence(
                reads,
                mock(DocumentPageImages.class),
                mock(DocumentPageImageCropper.class),
                facts);

        var visualFacts = adapter.readPageFacts(versionId, evidenceId, 4);

        assertThat(visualFacts).singleElement().satisfies(value -> {
            assertThat(value.printedTerms()).contains("current board label");
            assertThat(value.literalSummary()).doesNotContain("obsolete");
        });
    }

    @Test
    void requiresTheCanonicalEvidenceHandleToCoverTheRequestedPage() {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AssistantReadTools reads = mock(AssistantReadTools.class);
        when(reads.readRuleEvidenceIds(versionId, Set.of(evidenceId))).thenReturn(List.of(new RuleEvidence(
                evidenceId, versionId, "SETUP", "Setup", "Place components.", 4, 4)));
        DocumentPageImages images = mock(DocumentPageImages.class);
        when(images.read(versionId, Set.of(4))).thenReturn(List.of(
                new DocumentPageImages.PageImage(4, "image/png", new byte[] {1, 2}, 1000, 1200)));
        DocumentPageImageCropper cropper = mock(DocumentPageImageCropper.class);
        when(cropper.crop(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new byte[] {3, 4});
        VisualRulebookPageFacts facts = mock(VisualRulebookPageFacts.class);
        when(facts.find(versionId, Set.of(4))).thenReturn(List.of(new PageFact(
                4,
                "board zones",
                "A board with marked zones.",
                List.of("board", "zones"),
                List.of(new VisualAnchor("BOARD", "Board", "Marked zones", 50, 50, 600, 600)),
                PageFact.CURRENT_SCHEMA_VERSION)));
        var adapter = new DocumentNativeVisualEvidence(reads, images, cropper, facts);

        var page = adapter.readPage(versionId, evidenceId, 4);
        var crop = adapter.cropPage(versionId, evidenceId, 4, 100, 100, 300, 200);
        var visualFacts = adapter.readPageFacts(versionId, evidenceId, 4);

        assertThat(page).isPresent().get().satisfies(value ->
                assertThat(value.pageNumber()).isEqualTo(4));
        assertThat(crop).isPresent().get().satisfies(value -> {
            assertThat(value.pixelWidth()).isEqualTo(300);
            assertThat(value.pixelHeight()).isEqualTo(240);
        });
        assertThat(visualFacts).singleElement().satisfies(value -> {
            assertThat(value.literalSummary()).contains("marked zones");
            assertThat(value.anchors()).singleElement().satisfies(anchor ->
                    assertThat(anchor.label()).isEqualTo("Board"));
        });
        verify(cropper).crop(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(100),
                org.mockito.ArgumentMatchers.eq(100),
                org.mockito.ArgumentMatchers.eq(300),
                org.mockito.ArgumentMatchers.eq(200),
                org.mockito.ArgumentMatchers.eq(0));

        assertThat(adapter.readPage(versionId, evidenceId, 5)).isEmpty();
        assertThat(adapter.readPage(UUID.randomUUID(), evidenceId, 4)).isEmpty();
        assertThat(adapter.readPage(versionId, UUID.randomUUID(), 4)).isEmpty();
    }
}
