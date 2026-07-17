package com.rulepilot.document;

import java.util.UUID;

public record DocumentUploaded(UUID documentVersionId) {

    public DocumentUploaded {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("document version is required");
        }
    }
}
