package com.rulepilot.catalog.adapter.out.cover;

import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile;
import java.net.URI;

/** Fetches and bounds one public cover source before it can enter durable storage. */
public interface CoverImageFetcher {

    Thumbnail fetch(URI source, Profile profile);

    final class SourceAbsentException extends RuntimeException {
        public SourceAbsentException(String message) {
            super(message);
        }
    }
}
