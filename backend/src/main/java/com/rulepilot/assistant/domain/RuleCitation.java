package com.rulepilot.assistant.domain;

import java.util.UUID;

public record RuleCitation(
        UUID chunkId,
        UUID documentVersionId,
        String sectionType,
        String heading,
        String excerpt,
        int pageFrom,
        int pageTo) {}
