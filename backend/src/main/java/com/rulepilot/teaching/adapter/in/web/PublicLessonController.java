package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.teaching.application.PublicLessonCatalog;
import com.rulepilot.teaching.application.PublicLessonReader;
import com.rulepilot.teaching.application.PublicLessonQuestionService;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.application.PublicCoverThumbnailService;
import com.rulepilot.teaching.application.RulebookIconGlossaryService;
import com.rulepilot.assistant.PlayerLocale;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous reader for public lessons, cited pages, official source links, and bounded cover thumbnails. */
@RestController
@RequestMapping("/api/public/lessons")
@Profile("!test")
public class PublicLessonController {

    private final PublicLessonReader lessons;
    private final PublicLessonCatalog catalog;
    private final PublicLessonQuestionService questions;
    private final LessonLocalizationService localizations;
    private final PublicCoverThumbnailService coverThumbnails;
    private final DocumentPageImages pageImages;
    private final DocumentPageImageCropper crops;
    private final RulebookIconGlossaryService iconGlossary;

    public PublicLessonController(
            PublicLessonReader lessons,
            PublicLessonCatalog catalog,
            PublicLessonQuestionService questions,
            LessonLocalizationService localizations,
            PublicCoverThumbnailService coverThumbnails,
            DocumentPageImages pageImages,
            DocumentPageImageCropper crops,
            RulebookIconGlossaryService iconGlossary) {
        this.lessons = lessons;
        this.catalog = catalog;
        this.questions = questions;
        this.localizations = localizations;
        this.coverThumbnails = coverThumbnails;
        this.pageImages = pageImages;
        this.crops = crops;
        this.iconGlossary = iconGlossary;
    }

    @GetMapping
    List<PublicLessonCatalog.Entry> catalog(@RequestParam(defaultValue = "24") int limit) {
        return catalog.latest(limit);
    }

    @GetMapping("/{planId}")
    PublicLessonResponse lesson(@PathVariable UUID planId, @RequestParam(defaultValue = "zh-CN") String language) {
        var source = require(planId);
        var localized = localizations.view(source.lesson(), PlayerLocale.fromRequest(language));
        return PublicLessonResponse.from(source, localized);
    }

    @PostMapping("/{planId}/answers")
    PublicLessonQuestionService.PublicAnswer answer(
            @PathVariable UUID planId,
            @org.springframework.web.bind.annotation.RequestBody PublicLessonQuestionService.QuestionRequest request) {
        return questions.answer(planId, request).orElseThrow(this::notFound);
    }

    @GetMapping("/{planId}/icon-glossary")
    RulebookIconGlossaryService.GlossaryView iconGlossary(@PathVariable UUID planId) {
        require(planId);
        return iconGlossary.viewPublic(planId);
    }

    @GetMapping("/{planId}/icon-glossary/icons/{occurrenceId}/image")
    ResponseEntity<byte[]> iconImage(@PathVariable UUID planId, @PathVariable UUID occurrenceId) {
        require(planId);
        var crop = iconGlossary.cropPublic(planId, occurrenceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(crop.mediaType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(crop.content());
    }

    @GetMapping("/{planId}/rulebook")
    ResponseEntity<Void> officialRulebook(@PathVariable UUID planId) {
        String sourceUrl = require(planId).officialSourceUrl();
        if (sourceUrl == null) throw notFound();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(sourceUrl)).build();
    }

    @GetMapping("/{planId}/cover")
    ResponseEntity<byte[]> cover(@PathVariable UUID planId) {
        var lesson = require(planId);
        try {
            var thumbnail = lesson.gameCover() == null
                    ? rulebookCover(lesson)
                    : coverThumbnails.thumbnailFor(lesson.gameCover().imageUrl());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .body(thumbnail.content());
        } catch (RuntimeException unavailable) {
            if (lesson.gameCover() != null) {
                try {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                            .body(rulebookCover(lesson).content());
                } catch (RuntimeException fallbackUnavailable) {
                    // Continue with the same client-facing availability response below.
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "public cover is temporarily unavailable");
        }
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image")
    ResponseEntity<byte[]> pageImage(@PathVariable UUID planId, @PathVariable int pageNumber) {
        var image = image(planId, pageNumber);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(crops.crop(image, 0, 0, 1_000, 1_000, 0));
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

    private com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail rulebookCover(
            PublicLessonReader.PublicLesson lesson) {
        var firstPage = pageImages.read(lesson.documentVersionId(), Set.of(1)).stream()
                .filter(image -> image.pageNumber() == 1)
                .findFirst()
                .orElseThrow(this::notFound);
        return coverThumbnails.thumbnailForRulebookCover(lesson.documentVersionId(), firstPage);
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
            PublicLessonReader.PublicCover gameCover,
            com.rulepilot.teaching.domain.IllustratedLesson lesson,
            String contentLanguage,
            String localizationStatus) {
        static PublicLessonResponse from(
                PublicLessonReader.PublicLesson source, LessonLocalizationService.LocalizationView localized) {
            return new PublicLessonResponse(
                    source.teachingPlanId(),
                    source.documentVersionId(),
                    source.rulebookTitle(),
                    source.officialSourceUrl(),
                    source.gameCover(),
                    localized.lesson(),
                    localized.lesson() == null ? "zh-CN" : localized.language() == PlayerLocale.EN
                            && localized.status() == com.rulepilot.teaching.domain.LessonLocalization.Status.READY ? "en" : "zh-CN",
                    localized.status() == null ? "NOT_PREPARED" : localized.status().name());
        }
    }
}
