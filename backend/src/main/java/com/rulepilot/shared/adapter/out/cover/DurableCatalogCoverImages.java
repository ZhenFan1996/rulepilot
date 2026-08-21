package com.rulepilot.shared.adapter.out.cover;

import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.shared.cover.DurableCoverThumbnailService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Infrastructure implementation of the catalog-owned durable cover port. */
@Component
@Profile("!test")
public class DurableCatalogCoverImages implements CatalogCoverImages {

    private final DurableCoverThumbnailService thumbnails;

    public DurableCatalogCoverImages(DurableCoverThumbnailService thumbnails) {
        this.thumbnails = thumbnails;
    }

    @Override
    public byte[] read(String sourceUrl) {
        return thumbnails.thumbnailFor(sourceUrl).content();
    }
}
