package com.rulepilot.catalog;

import java.util.Optional;

/** Translates public BGG description metadata without exposing the provider credential to the browser. */
public interface BggDescriptionTranslation {

    Optional<String> translate(int bggId, String gameName, String sourceDescription);
}
