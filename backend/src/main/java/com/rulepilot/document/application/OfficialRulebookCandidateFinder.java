package com.rulepilot.document.application;

import com.rulepilot.agenttrace.CaptureHandle;
import java.util.List;
import java.util.UUID;

public interface OfficialRulebookCandidateFinder {

    boolean configured();

    List<Candidate> find(Request request);

    /** Captures one authenticated discovery turn without making private diagnostics a product dependency. */
    default List<Candidate> find(Request request, CaptureHandle capture, UUID parentOperationId) {
        return find(request);
    }

    /** One bounded recovery pass after ordinary search and source-page inspection produced no downloadable PDF. */
    default List<Candidate> findAfterSourcePages(Request request, List<Candidate> observedSourcePages) {
        return List.of();
    }

    default List<Candidate> findAfterSourcePages(
            Request request,
            List<Candidate> observedSourcePages,
            CaptureHandle capture,
            UUID parentOperationId) {
        return findAfterSourcePages(request, observedSourcePages);
    }

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

    record Candidate(
            String title,
            String url,
            String publisher,
            String language,
            String edition,
            SourcePageHint sourcePageHint) {
        public Candidate {
            sourcePageHint = sourcePageHint == null ? SourcePageHint.UNVERIFIED_PAGE : sourcePageHint;
        }

        public Candidate(String title, String url, String publisher, String language, String edition) {
            this(title, url, publisher, language, edition, SourcePageHint.UNVERIFIED_PAGE);
        }
    }

    /** A provider-owned structural hint; it never upgrades a page to an importable document. */
    enum SourcePageHint {
        UNVERIFIED_PAGE,
        DOCUMENT_LISTING,
        GAME_INFORMATION
    }
}
