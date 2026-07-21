package com.rulepilot.teaching;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualRegionLocatorTest {

    @Test
    void rejects_a_region_without_existing_evidence_support() {
        assertThatThrownBy(() -> new VisualRegionLocator.LocatedRegion(
                2, "Probe track", 100, 100, 200, 200, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_an_oversized_or_out_of_page_region() {
        assertThatThrownBy(() -> new VisualRegionLocator.LocatedRegion(
                2, "Probe track", 950, 100, 100, 200, List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
