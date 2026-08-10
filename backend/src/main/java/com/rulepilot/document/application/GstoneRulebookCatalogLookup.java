package com.rulepilot.document.application;

import java.util.List;

/** Finds an exact game page in Gstone's public, non-authenticated catalog surfaces. */
public interface GstoneRulebookCatalogLookup {

    List<OfficialRulebookCandidateFinder.Candidate> find(OfficialRulebookCandidateFinder.Request request);
}
