package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogEditionLookup.EditionReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Keeps catalog, discovery, source-page, and persisted-document identities distinct until player review. */
public final class OfficialRulebookImportIdentity {

    private OfficialRulebookImportIdentity() {}

    public static Review review(
            EditionReference selected,
            SourceClaim source,
            Optional<EditionReference> discovered,
            List<PersistedInput> persistedInputs) {
        if (selected == null || source == null || discovered == null || persistedInputs == null) {
            throw new IllegalArgumentException("official rulebook identity review inputs are required");
        }
        EnumSet<Issue> issues = EnumSet.noneOf(Issue.class);
        if (source.discoveredForEditionId() == null || discovered.isEmpty()) {
            issues.add(Issue.DISCOVERY_IDENTITY_UNKNOWN);
        } else {
            EditionReference sourceContext = discovered.orElseThrow();
            if (!selected.gameId().equals(sourceContext.gameId())) {
                issues.add(Issue.GAME_IDENTITY_CONFLICT);
            } else if (!selected.id().equals(sourceContext.id())) {
                issues.add(Issue.EDITION_IDENTITY_CONFLICT);
            }
        }

        if (source.edition() == null) {
            issues.add(Issue.SOURCE_EDITION_UNKNOWN);
        } else if (!displayIdentity(source.edition()).equals(displayIdentity(selected.name()))) {
            issues.add(Issue.SOURCE_EDITION_DIFFERS);
        }

        String selectedLanguage = canonicalCatalogLanguage(selected.language());
        if (selectedLanguage == null) {
            issues.add(Issue.CATALOG_LANGUAGE_UNKNOWN);
        }
        if (source.language() == null || !source.languageVerified()) {
            issues.add(Issue.SOURCE_LANGUAGE_UNKNOWN);
        } else if (selectedLanguage != null && !selectedLanguage.equals(source.language())) {
            issues.add(Issue.LANGUAGE_CONFLICT);
        }

        List<PersistedIdentity> persisted = new ArrayList<>();
        for (PersistedInput input : persistedInputs) {
            if (input == null) throw new IllegalArgumentException("persisted rulebook identity is required");
            EditionReference identity = input.identity();
            persisted.add(new PersistedIdentity(
                    input.source(),
                    input.editionId(),
                    identity == null ? null : CatalogIdentity.from(identity)));
            if (input.editionId() == null || identity == null) {
                issues.add(Issue.PERSISTED_EDITION_UNKNOWN);
            } else if (!selected.gameId().equals(identity.gameId())) {
                issues.add(Issue.PERSISTED_GAME_CONFLICT);
            } else if (!selected.id().equals(identity.id())) {
                issues.add(Issue.PERSISTED_EDITION_CONFLICT);
            }
        }

        return new Review(
                CatalogIdentity.from(selected),
                source,
                discovered.map(CatalogIdentity::from).orElse(null),
                persisted,
                new ArrayList<>(issues));
    }

    private static String displayIdentity(String value) {
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isWhitespace(current)) {
                pendingSpace = compact.length() > 0;
            } else {
                if (pendingSpace) compact.append(' ');
                compact.append(current);
                pendingSpace = false;
            }
        }
        return compact.toString();
    }

    private static String canonicalCatalogLanguage(String language) {
        if (language == null || language.isBlank()) return null;
        String canonical = Locale.forLanguageTag(language.strip().replace('_', '-')).toLanguageTag();
        return canonical.equalsIgnoreCase("und") ? null : canonical;
    }

    public record SourceClaim(
            UUID discoveredForEditionId,
            String edition,
            String language,
            boolean languageVerified) {
        public SourceClaim {
            edition = optionalBounded(edition, 120, "source edition");
            language = checkedLanguage(language);
            if (languageVerified && language == null) {
                throw new IllegalArgumentException("verified source language requires a valid language tag");
            }
        }

        public static SourceClaim unknown() {
            return new SourceClaim(null, null, null, false);
        }

        private static String optionalBounded(String value, int maximum, String field) {
            if (value == null || value.isBlank()) return null;
            String checked = value.strip();
            if (checked.length() > maximum) throw new IllegalArgumentException(field + " is too long");
            return checked;
        }

        private static String checkedLanguage(String language) {
            if (language == null || language.isBlank()) return null;
            String normalized = language.strip().replace('_', '-');
            if (normalized.length() > 20
                    || !normalized.matches("(?i)[a-z]{2,3}(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?")) {
                throw new IllegalArgumentException("source language must be a valid language tag");
            }
            String canonical = Locale.forLanguageTag(normalized).toLanguageTag();
            if (canonical.equalsIgnoreCase("und")) {
                throw new IllegalArgumentException("source language must be a known language tag");
            }
            return canonical;
        }
    }

    public record CatalogIdentity(
            UUID editionId,
            UUID gameId,
            String gameName,
            String editionName,
            String language) {
        static CatalogIdentity from(EditionReference reference) {
            return new CatalogIdentity(
                    reference.id(), reference.gameId(), reference.gameName(), reference.name(), reference.language());
        }
    }

    public record PersistedInput(
            PersistedSource source,
            UUID editionId,
            EditionReference identity) {
        public PersistedInput {
            if (source == null) throw new IllegalArgumentException("persisted identity source is required");
        }
    }

    public record PersistedIdentity(
            PersistedSource source,
            UUID editionId,
            CatalogIdentity identity) {}

    public record Review(
            CatalogIdentity selected,
            SourceClaim source,
            CatalogIdentity discovered,
            List<PersistedIdentity> persisted,
            List<Issue> issues) {
        public Review {
            if (selected == null || source == null || persisted == null || issues == null) {
                throw new IllegalArgumentException("official rulebook identity review is invalid");
            }
            persisted = List.copyOf(persisted);
            issues = List.copyOf(issues);
        }

        public boolean confirmationRequired() {
            return !issues.isEmpty();
        }
    }

    public enum Issue {
        DISCOVERY_IDENTITY_UNKNOWN,
        GAME_IDENTITY_CONFLICT,
        EDITION_IDENTITY_CONFLICT,
        SOURCE_EDITION_UNKNOWN,
        SOURCE_EDITION_DIFFERS,
        CATALOG_LANGUAGE_UNKNOWN,
        SOURCE_LANGUAGE_UNKNOWN,
        LANGUAGE_CONFLICT,
        PERSISTED_EDITION_UNKNOWN,
        PERSISTED_GAME_CONFLICT,
        PERSISTED_EDITION_CONFLICT
    }

    public enum PersistedSource {
        IMPORT_JOB,
        DOCUMENT
    }
}
