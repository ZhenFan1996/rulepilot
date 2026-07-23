package com.rulepilot.teaching;

import java.net.URI;

/** Fetches a validated remote cover and turns it into the bounded thumbnail used by readers. */
public interface PublicCoverImageFetcher {

    PublicCoverThumbnailCache.Thumbnail fetch(URI source);
}
