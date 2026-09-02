package com.rulepilot.visualaid.application;

import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import java.util.List;
import java.util.UUID;

/** Durable state owned by the optional visual-aid module. */
public interface VisualRegionIndex {

    void replace(UUID documentVersionId, String source, int pageCount, List<Region> regions);
}
