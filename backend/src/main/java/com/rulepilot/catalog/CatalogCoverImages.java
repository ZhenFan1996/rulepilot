package com.rulepilot.catalog;

/** Reads one bounded, durably cached cover image for a trusted catalog source URL. */
public interface CatalogCoverImages {

    byte[] read(String sourceUrl);
}
