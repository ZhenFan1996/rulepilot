package com.rulepilot.shared.cover;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import com.rulepilot.shared.cover.DurableCoverThumbnailService.Profile;
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
