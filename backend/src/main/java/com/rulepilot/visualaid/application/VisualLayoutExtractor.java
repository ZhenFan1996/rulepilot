package com.rulepilot.visualaid.application;

import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import java.io.InputStream;
import java.util.List;

/** Plugin port for layout tools; implementations return geometry, never rule meaning or player prose. */
public interface VisualLayoutExtractor {

    Extraction extract(InputStream rulebookPdf);

    default boolean configured() {
        return true;
    }

    static VisualLayoutExtractor unavailable() {
        return new VisualLayoutExtractor() {
            @Override
            public Extraction extract(InputStream rulebookPdf) {
                throw new IllegalStateException("visual layout extraction is unavailable");
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }

    record Extraction(String source, int pageCount, List<Region> regions) {
        public Extraction {
            if (source == null || source.isBlank() || pageCount < 1 || regions == null
                    || regions.stream().anyMatch(region -> region == null || region.pageNumber() > pageCount)) {
                throw new IllegalArgumentException("visual layout extraction is invalid");
            }
            source = source.strip();
            regions = List.copyOf(regions);
        }
    }
}
