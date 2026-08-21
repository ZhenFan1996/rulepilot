package com.rulepilot.catalog.adapter.in.web;

import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.CatalogCoverImages;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Serves BGG cover artwork from RulePilot's durable bounded thumbnail cache. */
@RestController
@Profile("!test")
class BggCatalogCoverImageController {

    private final CatalogGameSelectionLookup games;
    private final CatalogCoverImages coverImages;

    BggCatalogCoverImageController(CatalogGameSelectionLookup games, CatalogCoverImages coverImages) {
        this.games = games;
        this.coverImages = coverImages;
    }

    @GetMapping("/api/v1/bgg/catalog/covers/{bggId}/image")
    ResponseEntity<byte[]> image(@PathVariable int bggId) {
        if (bggId <= 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog game does not exist");
        var game = games.find(bggId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog game does not exist"));
        String source = !game.imageUrl().isBlank() ? game.imageUrl() : game.thumbnailUrl();
        if (source.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog cover does not exist");
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                    .body(coverImages.read(source));
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "catalog cover is temporarily unavailable");
        }
    }
}
