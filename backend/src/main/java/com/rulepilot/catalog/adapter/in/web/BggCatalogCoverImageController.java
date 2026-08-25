package com.rulepilot.catalog.adapter.in.web;

import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogCoverImages.Absent;
import com.rulepilot.catalog.CatalogCoverImages.Asset;
import com.rulepilot.catalog.CatalogCoverImages.Ready;
import com.rulepilot.catalog.CatalogCoverImages.Retryable;
import com.rulepilot.catalog.CatalogCoverImages.Variant;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Projects the catalog-owned cover result onto stable same-origin HTTP URLs. */
@RestController
@Profile("!test")
class BggCatalogCoverImageController {

    private static final CacheControl READY_CACHE =
            CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
    private static final CacheControl FAILURE_CACHE = CacheControl.noStore();

    private final CatalogCoverImages coverImages;

    BggCatalogCoverImageController(CatalogCoverImages coverImages) {
        this.coverImages = coverImages;
    }

    @GetMapping("/api/v1/bgg/catalog/covers/{bggId}/image")
    ResponseEntity<byte[]> image(@PathVariable int bggId) {
        return response(coverImages.load(bggId, Variant.DISPLAY));
    }

    @GetMapping("/api/v1/bgg/catalog/covers/{bggId}/thumbnail")
    ResponseEntity<byte[]> thumbnail(@PathVariable int bggId) {
        return response(coverImages.load(bggId, Variant.COMPACT));
    }

    private ResponseEntity<byte[]> response(Asset asset) {
        if (asset instanceof Ready ready) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(ready.contentType().mediaType()))
                    .eTag(ready.entityTag())
                    .cacheControl(READY_CACHE)
                    .body(ready.content());
        }
        if (asset instanceof Retryable retryable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(FAILURE_CACHE)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(retryable.retryAfter().toSeconds()))
                    .build();
        }
        if (asset instanceof Absent) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(FAILURE_CACHE)
                    .build();
        }
        throw new IllegalStateException("catalog cover result is unsupported");
    }
}
