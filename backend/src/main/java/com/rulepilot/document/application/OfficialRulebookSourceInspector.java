package com.rulepilot.document.application;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Reads one bounded public source to distinguish a PDF, ordered page-image document, or HTML source page. */
public interface OfficialRulebookSourceInspector {

    Optional<Inspection> inspect(URI source);

    record Inspection(URI finalSource, MediaType mediaType, List<Link> links) {
        public Inspection {
            if (finalSource == null || mediaType == null) {
                throw new IllegalArgumentException("rulebook source inspection metadata is required");
            }
            links = links == null ? List.of() : List.copyOf(links);
            if (mediaType != MediaType.HTML && !links.isEmpty()) {
                throw new IllegalArgumentException("a downloadable inspection cannot expose HTML links");
            }
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
}
