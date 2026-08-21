package com.rulepilot.catalog;

import java.util.List;
import java.util.Optional;

public interface CatalogGameSelectionLookup {

    Optional<GameSelection> find(int bggId);

    List<GameSelection> search(String query, int maximum);

    record GameSelection(
            int bggId,
            String name,
            String chineseName,
            Integer publicationYear,
            String thumbnailUrl,
            String imageUrl) {}
}
