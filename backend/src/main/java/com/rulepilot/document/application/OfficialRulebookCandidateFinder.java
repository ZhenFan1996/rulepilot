package com.rulepilot.document.application;

import java.util.List;

public interface OfficialRulebookCandidateFinder {

    boolean configured();

    List<Candidate> find(Request request);

    record Request(
            int bggId,
            String gameName,
            String editionName,
            Integer publicationYear,
            String language,
            List<String> officialNames,
            List<String> publishers,
            List<String> trustedDomains) {
        public Request {
            officialNames = officialNames == null ? List.of() : List.copyOf(officialNames);
            publishers = publishers == null ? List.of() : List.copyOf(publishers);
            trustedDomains = trustedDomains == null ? List.of() : List.copyOf(trustedDomains);
        }

        public Request(int bggId, String gameName, String editionName, Integer publicationYear, String language) {
            this(bggId, gameName, editionName, publicationYear, language, List.of(gameName), List.of(), List.of());
        }
    }

    record Candidate(String title, String url, String publisher, String language, String edition) {}
}
