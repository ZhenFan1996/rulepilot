package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.document.DocumentPageImageCropper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.RetryableDocumentProcessingException;
import com.rulepilot.teaching.application.PublicLessonCatalog;
import com.rulepilot.teaching.application.PublicLessonReader;
import com.rulepilot.teaching.application.PublicLessonQuestionService;
import com.rulepilot.teaching.application.LessonLocalizationService;
import com.rulepilot.teaching.application.PublicCoverThumbnailService;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
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

    private static final CacheControl PUBLIC_PAGE_IMAGE_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate();
    private static final String VISUAL_FAILURE_HEADER = "X-RulePilot-Visual-Failure";

    private final PublicLessonReader lessons;
    private final PublicLessonCatalog catalog;
    private final PublicLessonQuestionService questions;
    private final LessonLocalizationService localizations;
    private final PublicCoverThumbnailService coverThumbnails;
    private final DocumentPageImages pageImages;
    private final DocumentPageImageCropper crops;

    public PublicLessonController(
            PublicLessonReader lessons,
            PublicLessonCatalog catalog,
            PublicLessonQuestionService questions,
            LessonLocalizationService localizations,
            PublicCoverThumbnailService coverThumbnails,
            DocumentPageImages pageImages,
            DocumentPageImageCropper crops) {
        this.lessons = lessons;
        this.catalog = catalog;
        this.questions = questions;
        this.localizations = localizations;
        this.coverThumbnails = coverThumbnails;
        this.pageImages = pageImages;
        this.crops = crops;
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

    @GetMapping("/{planId}/rulebook")
    ResponseEntity<Void> officialRulebook(@PathVariable UUID planId) {
        String sourceUrl = require(planId).officialSourceUrl();
        if (sourceUrl == null) throw notFound();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(sourceUrl)).build();
    }

    @GetMapping("/{planId}/cover")
    ResponseEntity<byte[]> cover(@PathVariable UUID planId) {
        var cached = catalog.cachedCover(planId);
        var lesson = cached.isEmpty() ? require(planId) : null;
        var gameCover = cached.map(PublicLessonCatalog.CachedCover::gameCover)
                .orElseGet(() -> lesson == null ? null : lesson.gameCover());
        UUID documentVersionId = cached.map(PublicLessonCatalog.CachedCover::documentVersionId)
                .orElseGet(() -> lesson == null ? null : lesson.documentVersionId());
        try {
            var thumbnail = gameCover == null
                    ? rulebookCover(documentVersionId)
                    : coverThumbnails.thumbnailFor(gameCover.imageUrl());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .body(thumbnail.content());
        } catch (RuntimeException unavailable) {
            if (gameCover != null) {
                try {
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                            .body(rulebookCover(documentVersionId).content());
                } catch (RuntimeException fallbackUnavailable) {
                    // Continue with the same client-facing availability response below.
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "public cover is temporarily unavailable");
        }
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image")
    ResponseEntity<byte[]> pageImage(@PathVariable UUID planId, @PathVariable int pageNumber) {
        return publicPageImage(() -> {
            var image = image(planId, pageNumber);
            return crops.crop(image, 0, 0, 1_000, 1_000, 0);
        });
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image/crop")
    ResponseEntity<byte[]> crop(
            @PathVariable UUID planId,
            @PathVariable int pageNumber,
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam int width,
            @RequestParam int height) {
        return publicPageImage(() -> crops.crop(image(planId, pageNumber), x, y, width, height));
    }

    @GetMapping("/{planId}/pages/{pageNumber}/image/preview")
    ResponseEntity<byte[]> pageImagePreview(@PathVariable UUID planId, @PathVariable int pageNumber) {
        return publicPageImage(() -> crops.preview(image(planId, pageNumber)));
    }

    private ResponseEntity<byte[]> publicPageImage(Supplier<byte[]> content) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(PUBLIC_PAGE_IMAGE_CACHE)
                    .body(content.get());
        } catch (ResponseStatusException classified) {
            throw classified;
        } catch (RejectedExecutionException saturated) {
            return retryablePageImageFailure("DECODE_CAPACITY_EXCEEDED");
        } catch (RetryableDocumentProcessingException transientFailure) {
            return retryablePageImageFailure("PAGE_IMAGE_TEMPORARILY_UNAVAILABLE");
        } catch (IllegalArgumentException invalidRequest) {
            throw invalidRequest;
        } catch (RuntimeException unreadable) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .cacheControl(CacheControl.noStore())
                    .header(VISUAL_FAILURE_HEADER, "PAGE_IMAGE_UNAVAILABLE")
                    .body(new byte[0]);
        }
    }

    private ResponseEntity<byte[]> retryablePageImageFailure(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .header("Retry-After", "1")
                .header(VISUAL_FAILURE_HEADER, reason)
                .body(new byte[0]);
    }

    private DocumentPageImages.PageImage image(UUID planId, int pageNumber) {
        var lesson = requireCitedPage(planId, pageNumber);
        return pageImages.read(lesson.documentVersionId(), Set.of(pageNumber)).stream()
                .findFirst()
                .orElseThrow(this::notFound);
    }

    private com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail rulebookCover(UUID documentVersionId) {
        var firstPage = pageImages.read(documentVersionId, Set.of(1)).stream()
                .filter(image -> image.pageNumber() == 1)
                .findFirst()
                .orElseThrow(this::notFound);
        return coverThumbnails.thumbnailForRulebookCover(documentVersionId, firstPage);
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
            PublicGameIdentityLookup.Identity publicGame,
            List<String> unresolvedTopics,
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
                    source.publicGame(),
                    source.unresolvedTopics(),
                    localized.lesson(),
                    localized.lesson() == null ? "zh-CN" : localized.language() == PlayerLocale.EN
                            && localized.status() == com.rulepilot.teaching.domain.LessonLocalization.Status.READY ? "en" : "zh-CN",
                    localized.status() == null ? "NOT_PREPARED" : localized.status().name());
        }
    }
}
