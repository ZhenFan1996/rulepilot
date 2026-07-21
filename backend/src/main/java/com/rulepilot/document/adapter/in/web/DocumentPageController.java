package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImages;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/pages")
@Profile("!test")
public class DocumentPageController {

    private final DocumentProcessing documents;
    private final DocumentPageImages pageImages;
    private final RulePageImageCropper imageCropper;

    public DocumentPageController(
            DocumentProcessing documents,
            DocumentPageImages pageImages,
            RulePageImageCropper imageCropper) {
        this.documents = documents;
        this.pageImages = pageImages;
        this.imageCropper = imageCropper;
    }

    @GetMapping
    List<PageResponse> pages(@PathVariable UUID versionId) {
        return documents.pages(versionId).stream().map(PageResponse::from).toList();
    }

    @GetMapping("/{pageNumber}/image")
    ResponseEntity<byte[]> pageImage(@PathVariable UUID versionId, @PathVariable int pageNumber) {
        var image = pageImages.read(versionId, Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page image does not exist"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(image.content());
    }

    @GetMapping("/{pageNumber}/image/crop")
    ResponseEntity<byte[]> pageImageCrop(
            @PathVariable UUID versionId,
            @PathVariable int pageNumber,
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam int width,
            @RequestParam int height) {
        var image = pageImages.read(versionId, Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("document page image does not exist"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(imageCropper.crop(image, x, y, width, height));
    }

    record PageResponse(int pageNumber, String text, int characterCount) {
        static PageResponse from(DocumentProcessing.PageView page) {
            return new PageResponse(page.pageNumber(), page.text(), page.characterCount());
        }
    }
}
