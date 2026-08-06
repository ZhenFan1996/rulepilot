package com.rulepilot.document.application;

import java.util.List;

public interface OfficialRulebookCandidateFinder {

    boolean configured();

    List<Candidate> find(Request request);

    record Request(String gameName, String editionName, Integer publicationYear, String language) {}

    record Candidate(String title, String url, String publisher, String language, String edition) {}
}
