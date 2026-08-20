package com.rulepilot.catalog;

import java.util.Optional;

public interface CatalogGameSelectionLookup {

    Optional<GameSelection> find(int bggId);

    record GameSelection(int bggId, String name, String chineseName, String thumbnailUrl) {}
}
