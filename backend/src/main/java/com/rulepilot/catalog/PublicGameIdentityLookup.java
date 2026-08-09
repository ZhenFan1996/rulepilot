package com.rulepilot.catalog;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Exact local-snapshot BGG identity for decorating public lessons; never rule evidence. */
public interface PublicGameIdentityLookup {

    Optional<Identity> findByTitle(String title);

    Map<String, Identity> findByTitles(Collection<String> titles);

    record Identity(int bggId, String name, String bggUrl) {
        public Identity {
            if (bggId <= 0 || name == null || name.isBlank() || bggUrl == null || bggUrl.isBlank()) {
                throw new IllegalArgumentException("public game identity is invalid");
            }
            name = name.strip();
            URI uri = URI.create(bggUrl.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("public game identity BGG URL must use HTTPS");
            }
            bggUrl = uri.toASCIIString();
        }
    }
}
