package com.rulepilot.document.application;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reads one bounded public source to distinguish a PDF, ordered page-image document, or HTML source page. */
public interface OfficialRulebookSourceInspector {

    Optional<Inspection> inspect(URI source);

    record Inspection(URI finalSource, MediaType mediaType, List<Link> links, Set<PageSignal> pageSignals) {
        public Inspection {
            if (finalSource == null || mediaType == null) {
                throw new IllegalArgumentException("rulebook source inspection metadata is required");
            }
            links = links == null ? List.of() : List.copyOf(links);
            pageSignals = pageSignals == null
                    ? Set.of()
                    : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(pageSignals));
            if (mediaType != MediaType.HTML && !links.isEmpty()) {
                throw new IllegalArgumentException("a downloadable inspection cannot expose HTML links");
            }
            if (mediaType != MediaType.HTML && !pageSignals.isEmpty()) {
                throw new IllegalArgumentException("a downloadable inspection cannot expose HTML page signals");
            }
        }

        public Inspection(URI finalSource, MediaType mediaType, List<Link> links) {
            this(finalSource, mediaType, links, Set.of());
        }
    }

    record Link(URI target, String label) {
        public Link {
            if (target == null) throw new IllegalArgumentException("rulebook source link target is required");
            label = label == null ? "" : label.strip();
        }
    }

    enum MediaType {
        PDF,
        IMAGE_GALLERY,
        HTML
    }

    /** Game-independent facts observed from the fetched HTML rather than inferred from a candidate title. */
    enum PageSignal {
        DOWNLOADABLE_DOCUMENT_LINKS,
        EXPLICIT_EMPTY_DOCUMENT_COLLECTION,
        STRUCTURED_GAME_INFORMATION,
        LOGIN_REQUIRED
    }
}
