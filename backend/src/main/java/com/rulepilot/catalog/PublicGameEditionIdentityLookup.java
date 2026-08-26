package com.rulepilot.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Exact BGG identity bound to an imported catalog edition. */
public interface PublicGameEditionIdentityLookup {

    Map<UUID, Integer> findBggIds(Collection<UUID> editionIds);
}
