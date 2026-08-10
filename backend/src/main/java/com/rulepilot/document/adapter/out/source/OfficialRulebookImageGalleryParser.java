package com.rulepilot.document.adapter.out.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Identifies ordered rulebook-page images inside an explicitly marked document viewer. */
final class OfficialRulebookImageGalleryParser {

    static final int MAX_PAGE_COUNT = 40;
    private static final int MIN_PAGE_COUNT = 2;
    private static final Pattern GSTONE_DOCUMENT_PATH = Pattern.compile("^/game/doc-[0-9]+\\.html$");
    private static final Set<String> RULEBOOK_TERMS = Set.of(
            "rulebook", "rules", "manual", "instructions", "regles", "regeln", "regolamento", "reglas",
            "规则", "規則", "说明书", "說明書");
    private static final String EXPLICIT_PAGE_CONTAINERS = String.join(", ",
            "[data-rulebook-pages]",
            "#rulebook-pages",
            ".rulebook-pages",
            "#manual-pages",
            ".manual-pages",
            "#document-pages",
            ".document-pages");
    private static final List<String> IMAGE_ATTRIBUTES =
            List.of("data-original", "data-src", "data-lazy-src", "src");

    Optional<Gallery> parse(URI source, Document document) {
        if (source == null || source.getHost() == null || document == null) return Optional.empty();
        Element container = pageContainer(source, document);
        if (container == null) return Optional.empty();

        Map<String, URI> orderedPages = new LinkedHashMap<>();
        for (Element image : container.select("img")) {
            URI page = pageImage(source, image);
            if (page != null) orderedPages.putIfAbsent(page.toASCIIString(), page);
        }
        if (orderedPages.size() < MIN_PAGE_COUNT || orderedPages.size() > MAX_PAGE_COUNT) {
            return Optional.empty();
        }
        return Optional.of(new Gallery(List.copyOf(orderedPages.values())));
    }

    private Element pageContainer(URI source, Document document) {
        String host = source.getHost().toLowerCase(Locale.ROOT);
        String path = source.getPath() == null ? "" : source.getPath();
        if (isDomain(host, "gstonegames.com") && GSTONE_DOCUMENT_PATH.matcher(path).matches()) {
            return document.selectFirst("#preview_imgs");
        }
        Element container = document.selectFirst(EXPLICIT_PAGE_CONTAINERS);
        if (container == null) return null;
        String semantics = (document.title() + " " + container.id() + " " + container.className())
                .toLowerCase(Locale.ROOT);
        return RULEBOOK_TERMS.stream().anyMatch(semantics::contains) ? container : null;
    }

    private URI pageImage(URI source, Element image) {
        for (String attribute : IMAGE_ATTRIBUTES) {
            if (!image.hasAttr(attribute) || image.attr(attribute).isBlank()) continue;
            URI candidate = publicHttps(image.absUrl(attribute));
            if (candidate == null || !sameSourceFamily(source, candidate) || !looksLikePageImage(candidate)) continue;
            return candidate;
        }
        return null;
    }

    private boolean sameSourceFamily(URI source, URI candidate) {
        String sourceHost = source.getHost().toLowerCase(Locale.ROOT);
        String candidateHost = candidate.getHost().toLowerCase(Locale.ROOT);
        if (sourceHost.equals(candidateHost)) return true;
        return isDomain(sourceHost, "gstonegames.com") && isDomain(candidateHost, "gstonegames.com");
    }

    private boolean looksLikePageImage(URI candidate) {
        String path = candidate.getPath() == null ? "" : candidate.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png");
    }

    private URI publicHttps(String value) {
        try {
            URI source = URI.create(value == null ? "" : value.strip());
            if (!"https".equalsIgnoreCase(source.getScheme())
                    || source.getHost() == null
                    || source.getUserInfo() != null
                    || source.getPort() != -1 && source.getPort() != 443) {
                return null;
            }
            return new URI(
                            source.getScheme(),
                            null,
                            source.getHost(),
                            source.getPort(),
                            source.getPath(),
                            source.getQuery(),
                            null)
                    .normalize();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return null;
        }
    }

    private boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    record Gallery(List<URI> pages) {
        Gallery {
            pages = List.copyOf(pages);
        }
    }
}
