package com.rulepilot.shared.cover;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import java.net.URI;

/** Fetches and bounds one public cover source before it can enter durable storage. */
public interface CoverImageFetcher {

    Thumbnail fetch(URI source);
}
