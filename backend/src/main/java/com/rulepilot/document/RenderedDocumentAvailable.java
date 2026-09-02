package com.rulepilot.document;

import java.util.UUID;

/** Published after every rendered page of an immutable document version is durably stored. */
public record RenderedDocumentAvailable(UUID documentVersionId, int pageCount) {

    public RenderedDocumentAvailable {
        if (documentVersionId == null || pageCount < 1) {
            throw new IllegalArgumentException("rendered document event is invalid");
        }
    }
}
