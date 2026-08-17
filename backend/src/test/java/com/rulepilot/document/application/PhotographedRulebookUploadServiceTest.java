package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.application.PhotographedRulebookUploadService.PhotoPage;
import com.rulepilot.document.domain.DocumentSourceType;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhotographedRulebookUploadServiceTest {

    private final PhotographedRulebookAssembler assembler = mock(PhotographedRulebookAssembler.class);
    private final UploadRuleDocumentService documents = mock(UploadRuleDocumentService.class);
    private final PhotographedRulebookUploadService service = new PhotographedRulebookUploadService(assembler, documents);

    @Test
    void usesTheExistingPdfUploadPipelineForPhotographedPages() {
        PhotoPage page = new PhotoPage("page.jpg", "image/jpeg", new byte[] {1, 2, 3});
        when(assembler.assemble(List.of(page))).thenReturn(new PhotographedRulebookAssembler.AssembledRulebook(
                "photographed-rulebook.pdf", new byte[] {4, 5, 6}));

        service.upload(null, null, DocumentSourceType.BASE_RULEBOOK, null, null, List.of(page), "alice");

        verify(documents).upload(
                eq(null),
                eq("Photographed rulebook"),
                eq(DocumentSourceType.BASE_RULEBOOK),
                eq(null),
                eq(null),
                eq("photographed-rulebook.pdf"),
                eq(RuleDocumentStorageService.PDF_CONTENT_TYPE),
                eq(3L),
                any(InputStream.class),
                eq("alice"),
                eq(false),
                isNull());
    }

    @Test
    void carriesAutomaticTeachingIntentThroughThePhotographedRulebookPipeline() {
        PhotoPage page = new PhotoPage("page.jpg", "image/jpeg", new byte[] {1, 2, 3});
        when(assembler.assemble(List.of(page))).thenReturn(new PhotographedRulebookAssembler.AssembledRulebook(
                "photographed-rulebook.pdf", new byte[] {4, 5, 6}));

        service.upload(
                null,
                null,
                DocumentSourceType.BASE_RULEBOOK,
                null,
                null,
                List.of(page),
                "alice",
                true,
                "重点讲组件摆放。");

        verify(documents).upload(
                eq(null),
                eq("Photographed rulebook"),
                eq(DocumentSourceType.BASE_RULEBOOK),
                eq(null),
                eq(null),
                eq("photographed-rulebook.pdf"),
                eq(RuleDocumentStorageService.PDF_CONTENT_TYPE),
                eq(3L),
                any(InputStream.class),
                eq("alice"),
                eq(true),
                eq("重点讲组件摆放。"));
    }

    @Test
    void acceptsARealisticallyLongPhotographedRulebookWithinThePdfBoundary() {
        List<PhotoPage> pages = new ArrayList<>();
        for (int page = 1; page <= 60; page++) {
            pages.add(new PhotoPage("page-" + page + ".jpg", "image/jpeg", new byte[] {(byte) page}));
        }
        when(assembler.assemble(pages)).thenReturn(new PhotographedRulebookAssembler.AssembledRulebook(
                "photographed-rulebook.pdf", new byte[] {4, 5, 6}));

        service.upload(null, "Long rulebook", DocumentSourceType.BASE_RULEBOOK, null, null, pages, "alice");

        verify(assembler).assemble(pages);
        verify(documents).upload(
                eq(null),
                eq("Long rulebook"),
                eq(DocumentSourceType.BASE_RULEBOOK),
                eq(null),
                eq(null),
                eq("photographed-rulebook.pdf"),
                eq(RuleDocumentStorageService.PDF_CONTENT_TYPE),
                eq(3L),
                any(InputStream.class),
                eq("alice"),
                eq(false),
                isNull());
    }

    @Test
    void rejectsAnUnsupportedPhotoBeforeItCanEnterThePdfPipeline() {
        PhotoPage page = new PhotoPage("page.webp", "image/webp", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.upload(
                        null, null, DocumentSourceType.BASE_RULEBOOK, null, null, List.of(page), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("photographed pages must be JPEG or PNG images");
        verify(assembler, org.mockito.Mockito.never()).assemble(any());
    }
}
