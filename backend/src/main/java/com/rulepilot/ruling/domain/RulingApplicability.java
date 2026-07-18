package com.rulepilot.ruling.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record RulingApplicability(
        UUID editionId,
        UUID documentVersionId,
        Set<UUID> expansionIds,
        String expansionSetHash) {

    public RulingApplicability {
        if (editionId == null || documentVersionId == null || expansionIds == null) {
            throw new IllegalArgumentException("ruling applicability is invalid");
        }
        expansionIds = Set.copyOf(expansionIds);
        String expectedHash = expansionSetHash(expansionIds);
        if (expansionSetHash == null) {
            expansionSetHash = expectedHash;
        } else if (!expansionSetHash.equals(expectedHash)) {
            throw new IllegalArgumentException("ruling expansion hash is invalid");
        }
    }

    public static RulingApplicability of(
            UUID editionId, UUID documentVersionId, Set<UUID> expansionIds) {
        return new RulingApplicability(editionId, documentVersionId, expansionIds, null);
    }

    public static String expansionSetHash(Set<UUID> expansionIds) {
        Set<UUID> safeIds = expansionIds == null ? Set.of() : Set.copyOf(expansionIds);
        return hash(safeIds.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(",")));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
