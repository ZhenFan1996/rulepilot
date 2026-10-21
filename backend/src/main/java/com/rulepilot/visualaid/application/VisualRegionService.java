package com.rulepilot.visualaid.application;

import com.rulepilot.visualaid.VisualRegionCatalog;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads source picture candidates; retired layout protocols remain non-authoritative. */
@Service
@Profile("!test")
public class VisualRegionService implements VisualRegionCatalog {
    private final VisualRegionIndex index;
    private final VisualAidIndexer indexer;
    public VisualRegionService(VisualRegionIndex index, VisualAidIndexer indexer) {
        this.index = index;
        this.indexer = indexer;
    }
    @Override public List<Region> find(UUID version, Set<Integer> pages) {
        indexer.ensure(version);
        return index.find(version, pages).stream().filter(region -> ("PDF_IMAGE".equals(region.kind()) || "DOCLING_RAW_PICTURE".equals(region.kind()))).toList();
    }
}
