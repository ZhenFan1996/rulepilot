package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.DocumentVersionScopeLookup;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/pages")
@Profile("!test")
public class DocumentPageController {

    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final RulePageImageCropper imageCropper;
    private final DocumentVersionScopeLookup versions;

    public DocumentPageController(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            RulePageImageCropper imageCropper,
            DocumentVersionScopeLookup versions) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.imageCropper = imageCropper;
        this.versions = versions;
    }

    @GetMapping
    List<PageResponse> pages(@PathVariable UUID versionId, Principal principal) {
        requireOwned(versionId, principal);
        return documents.pages(versionId).stream().map(PageResponse::from).toList();
    }

    @GetMapping("/summaries")
    List<PageSummaryResponse> pageSummaries(@PathVariable UUID versionId, Principal principal) {
        requireOwned(versionId, principal);
        return documents.pages(versionId).stream().map(PageSummaryResponse::from).toList();
    }

    @GetMapping("/{pageNumber}/image")
    ResponseEntity<byte[]> pageImage(
            @PathVariable UUID versionId, @PathVariable int pageNumber, Principal principal) {
        requireOwned(versionId, principal);
        var image = pageImages.read(versionId, Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page image does not exist"));
        byte[] content = MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(image.mediaType())
                ? image.content()
                : imageCropper.crop(image, 0, 0, 1_000, 1_000, 0);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate())
                .body(content);
    }

    @GetMapping("/{pageNumber}/image/crop")
    ResponseEntity<byte[]> pageImageCrop(
            @PathVariable UUID versionId,
            @PathVariable int pageNumber,
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam int width,
            @RequestParam int height,
            Principal principal) {
        requireOwned(versionId, principal);
        var image = pageImages.read(versionId, Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page image does not exist"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(imageCropper.crop(image, x, y, width, height));
    }

    @GetMapping("/{pageNumber}/image/preview")
    ResponseEntity<byte[]> pageImagePreview(
            @PathVariable UUID versionId, @PathVariable int pageNumber, Principal principal) {
        requireOwned(versionId, principal);
        var image = pageImages.read(versionId, Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page image does not exist"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(imageCropper.preview(image));
    }

    private void requireOwned(UUID versionId, Principal principal) {
        String owner = principal == null ? "" : principal.getName();
        versions.findVersion(versionId)
                .filter(version -> version.createdBy().equals(owner))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "document version does not exist"));
    }

    record PageResponse(int pageNumber, String text, int characterCount) {
        static PageResponse from(DocumentProcessing.PageView page) {
            return new PageResponse(page.pageNumber(), page.text(), page.characterCount());
        }
    }


    record PageSummaryResponse(int pageNumber, int characterCount) {
        static PageSummaryResponse from(DocumentProcessing.PageView page) {
            return new PageSummaryResponse(page.pageNumber(), page.characterCount());
        }
    }
}
