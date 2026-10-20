package com.rulepilot.visualaid.application;

import com.rulepilot.visualaid.VisualRegionCatalog;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads original image placements; older inferred layout regions remain non-authoritative. */
@Service
@Profile("!test")
public class VisualRegionService implements VisualRegionCatalog {
    private final VisualRegionIndex index;
    public VisualRegionService(VisualRegionIndex index) { this.index = index; }
    @Override public List<Region> find(UUID version, Set<Integer> pages) {
        return index.find(version, pages).stream().filter(region -> "PDF_IMAGE".equals(region.kind())).toList();
    }
}
