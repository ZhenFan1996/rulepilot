package com.rulepilot.catalog;

import java.util.UUID;

/** Confirms source-backed edition metadata without allowing a source to overwrite known catalog data. */
public interface CatalogEditionLanguageConfirmation {

    boolean confirmIfUnknown(UUID editionId, String language);
}
