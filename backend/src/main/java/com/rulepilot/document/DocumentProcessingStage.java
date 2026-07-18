package com.rulepilot.document;

public enum DocumentProcessingStage {
    PARSE,
    CHUNK,
    EMBED;

    public DocumentProcessingStage next() {
        return switch (this) {
            case PARSE -> CHUNK;
            case CHUNK -> EMBED;
            case EMBED -> null;
        };
    }
}
