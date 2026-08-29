package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentPageImages.PageImage;
import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.RetryableDocumentProcessingException;
import com.rulepilot.document.DocumentVersionScopeLookup;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class DocumentPageControllerTest {

    private final DocumentProcessing documents = mock(DocumentProcessing.class);
    private final DocumentPageImages pageImages = mock(DocumentPageImages.class);
    private final DocumentVersionScopeLookup versions = mock(DocumentVersionScopeLookup.class);
    private final DocumentPageController controller =
            new DocumentPageController(documents, pageImages, new RulePageImageCropper(), versions);

    @Test
    void listsPageSummariesWithoutPublishingExtractedRulebookText() {
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        when(documents.pages(versionId)).thenReturn(List.of(
                new DocumentProcessing.PageView(1, "private extracted text", 22),
                new DocumentProcessing.PageView(2, "more private extracted text", 27)));

        var summaries = controller.pageSummaries(versionId, () -> "player");

        assertThat(summaries).extracting("pageNumber", "characterCount")
                .containsExactly(tuple(1, 22), tuple(2, 27));
        assertThat(summaries.getFirst().toString()).doesNotContain("private extracted text");
    }

    @Test
    void servesTheWholeEvidencePageAsANormalizedBrowserSafeJpeg() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        BufferedImage source = new BufferedImage(80, 120, BufferedImage.TYPE_4BYTE_ABGR);
        var graphics = source.createGraphics();
        graphics.setColor(new Color(20, 40, 80, 180));
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        when(pageImages.read(versionId, Set.of(1)))
                .thenReturn(List.of(new PageImage(1, "image/png", encoded.toByteArray(), 80, 120)));

        var response = controller.pageImage(versionId, 1, () -> "player");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertThat(decoded.getWidth()).isEqualTo(80);
        assertThat(decoded.getHeight()).isEqualTo(120);
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    void servesACompactOwnedPagePreviewForInlineVisualLocation() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        BufferedImage source = new BufferedImage(800, 1_200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", encoded);
        when(pageImages.read(versionId, Set.of(2)))
                .thenReturn(List.of(new PageImage(2, "image/jpeg", encoded.toByteArray(), 800, 1_200)));

        var response = controller.pageImagePreview(versionId, 2, () -> "player");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertThat(preview.getWidth()).isEqualTo(453);
        assertThat(preview.getHeight()).isEqualTo(680);
    }

    @Test
    void servesStoredJpegPagesWithoutASecondLossyEncodingAndAllowsPrivateBrowserCaching() {
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
        when(pageImages.read(versionId, Set.of(3)))
                .thenReturn(List.of(new PageImage(3, "image/jpeg", jpeg, 1_600, 2_400)));

        var response = controller.pageImage(versionId, 3, () -> "player");

        assertThat(response.getBody()).containsExactly(jpeg);
        assertThat(response.getHeaders().getCacheControl()).contains("private").contains("max-age");
    }

    @Test
    void classifiesPrivateCropDecodeCapacityAsRetryableWithoutChangingDocumentState() {
        UUID versionId = UUID.randomUUID();
        var stored = new PageImage(4, "image/png", new byte[] {1}, 800, 1_200);
        var cropper = mock(DocumentPageImageCropper.class);
        var cropController = new DocumentPageController(documents, pageImages, cropper, versions);
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        when(pageImages.read(versionId, Set.of(4))).thenReturn(List.of(stored));
        when(cropper.crop(stored, 100, 200, 300, 400))
                .thenThrow(new RejectedExecutionException("decode work is saturated"));

        var response = cropController.pageImageCrop(versionId, 4, 100, 200, 300, 400, () -> "player");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("X-RulePilot-Visual-Failure"))
                .isEqualTo("DECODE_CAPACITY_EXCEEDED");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void classifiesTransientPrivateCropStorageFailureAsRetryable() {
        UUID versionId = UUID.randomUUID();
        var cropper = mock(DocumentPageImageCropper.class);
        var cropController = new DocumentPageController(documents, pageImages, cropper, versions);
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        when(pageImages.read(versionId, Set.of(4)))
                .thenThrow(new RetryableDocumentProcessingException(
                        "page image storage is temporarily unavailable", new IllegalStateException("storage")));

        var response = cropController.pageImageCrop(versionId, 4, 100, 200, 300, 400, () -> "player");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("X-RulePilot-Visual-Failure"))
                .isEqualTo("PAGE_IMAGE_TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void classifiesPermanentlyUnreadablePrivateCropAsBadGatewayWithoutRetryAdvice() {
        UUID versionId = UUID.randomUUID();
        var stored = new PageImage(4, "image/png", new byte[] {1}, 800, 1_200);
        var cropper = mock(DocumentPageImageCropper.class);
        var cropController = new DocumentPageController(documents, pageImages, cropper, versions);
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "player", "Rules")));
        when(pageImages.read(versionId, Set.of(4))).thenReturn(List.of(stored));
        when(cropper.crop(stored, 100, 200, 300, 400))
                .thenThrow(new IllegalStateException("document page image cannot be decoded"));

        var response = cropController.pageImageCrop(versionId, 4, 100, 200, 300, 400, () -> "player");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(response.getHeaders().getFirst("X-RulePilot-Visual-Failure"))
                .isEqualTo("PAGE_IMAGE_UNAVAILABLE");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void hidesPagesFromAnotherOwner() {
        UUID versionId = UUID.randomUUID();
        when(versions.findVersion(versionId)).thenReturn(java.util.Optional.of(
                new DocumentVersionScopeLookup.VersionScope(versionId, null, "READY", "someone-else", "Rules")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.pages(versionId, () -> "player"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }
}
