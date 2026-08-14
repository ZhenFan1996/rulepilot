package com.rulepilot.document;

public enum DocumentProcessingStage {
    PARSE,
    CHUNK,
    EMBED;

    public DocumentProcessingStage next() {
        return switch (this) {
            // PARSE already persists chunks through RuleStructureService. New workers skip the legacy status-only
            // CHUNK delivery, while the enum and its transition remain readable for messages published before rollout.
            case PARSE -> EMBED;
            case CHUNK -> EMBED;
            case EMBED -> null;
        };
    }
}
