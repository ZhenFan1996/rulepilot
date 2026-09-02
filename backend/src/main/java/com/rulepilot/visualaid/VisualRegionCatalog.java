package com.rulepilot.visualaid;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Provider-neutral visual-object geometry prepared independently of the teaching Agent. */
public interface VisualRegionCatalog {

    List<Region> find(UUID documentVersionId, Set<Integer> pageNumbers);

    default boolean configured() {
        return true;
    }

    static VisualRegionCatalog empty() {
        return new VisualRegionCatalog() {
            @Override
            public List<Region> find(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of();
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }

    record Region(int pageNumber, String kind, int x, int y, int width, int height) {

        public Region {
            if (pageNumber < 1
                    || kind == null
                    || kind.isBlank()
                    || x < 0
                    || y < 0
                    || width < 20
                    || height < 20
                    || x + width > 1_000
                    || y + height > 1_000
                    || (x == 0 && y == 0 && width == 1_000 && height == 1_000)) {
                throw new IllegalArgumentException("visual aid region is invalid");
            }
            kind = kind.strip();
        }
    }
}
