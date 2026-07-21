package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.application.PublicLessonReader;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous lesson reader that exposes only lesson-cited page imagery and official publisher links. */
@RestController
@RequestMapping("/api/public/lessons")
@Profile("!test")
public class PublicLessonController {

    private final PublicLessonReader lessons;
    private final DocumentPageImages pageImages;
    private final DocumentPageImageCropper crops;

    public PublicLessonController(
            PublicLessonReader lessons, DocumentPageImages pageImages, DocumentPageImageCropper crops) {
        this.lessons = lessons;
        this.pageImages = pageImages;
        this.crops = crops;
    }

    @GetMapping("/{planId}")
    PublicLessonResponse lesson(@PathVariable UUID planId) {
        return PublicLessonResponse.from(require(planId));
    }

    @GetMapping("/{planId}/rulebook")
    ResponseEntity<Void> officialRulebook(@PathVariable UUID planId) {
        String sourceUrl = require(planId).officialSourceUrl();
        if (sourceUrl == null) throw notFound();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(sourceUrl)).build();
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image")
    ResponseEntity<byte[]> pageImage(@PathVariable UUID planId, @PathVariable int pageNumber) {
        var image = image(planId, pageNumber);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(image.content());
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image/crop")
    ResponseEntity<byte[]> crop(
            @PathVariable UUID planId,
            @PathVariable int pageNumber,
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam int width,
            @RequestParam int height) {
        var image = image(planId, pageNumber);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(crops.crop(image, x, y, width, height));
    }

    private DocumentPageImages.PageImage image(UUID planId, int pageNumber) {
        var lesson = requireCitedPage(planId, pageNumber);
        return pageImages.read(lesson.documentVersionId(), Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(this::notFound);
    }

    private PublicLessonReader.PublicLesson require(UUID planId) {
        return lessons.find(planId).orElseThrow(this::notFound);
    }

    private PublicLessonReader.PublicLesson requireCitedPage(UUID planId, int pageNumber) {
        try {
            return lessons.requireCitedPage(planId, pageNumber);
        } catch (IllegalArgumentException missing) {
            throw notFound();
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "public lesson does not exist");
    }

    record PublicLessonResponse(
            UUID teachingPlanId,
            UUID documentVersionId,
            String rulebookTitle,
            String officialSourceUrl,
            com.rulepilot.teaching.domain.IllustratedLesson lesson) {
        static PublicLessonResponse from(PublicLessonReader.PublicLesson lesson) {
            return new PublicLessonResponse(
                    lesson.teachingPlanId(),
                    lesson.documentVersionId(),
                    lesson.rulebookTitle(),
                    lesson.officialSourceUrl(),
                    lesson.lesson());
        }
    }
}
