package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class OfficialRulebookDiscoveryService {

    private final CatalogGamePresentationLookup catalog;
    private final OfficialRulebookCandidateFinder finder;
    private final Set<String> allowedDomains;

    public OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            OfficialRulebookCandidateFinder finder,
            @Value("${rulepilot.rulebook-discovery.allowed-domains:}") String allowedDomains) {
        this.catalog = catalog;
        this.finder = finder;
        this.allowedDomains = Arrays.stream(allowedDomains.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Result discover(UUID editionId, String language) {
        if (!finder.configured()) return new Result(false, List.of());
        var game = catalog.findByEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("catalog edition does not exist or has no BGG metadata"));
        String checkedLanguage = language == null || language.isBlank() ? game.language() : language.strip();
        var request = new OfficialRulebookCandidateFinder.Request(
                game.bggId(), game.gameName(), game.editionName(), game.publicationYear(), checkedLanguage);
        List<Candidate> candidates = finder.find(request).stream()
                .limit(8)
                .map(this::validate)
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(Candidate::officialDomainVerified).reversed())
                .toList();
        return new Result(true, candidates);
    }

    private Candidate validate(OfficialRulebookCandidateFinder.Candidate candidate) {
        if (candidate == null || candidate.url() == null || candidate.title() == null) return null;
        URI uri;
        try {
            uri = URI.create(candidate.url().strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443 || !looksLikePdf(uri)) {
            return null;
        }
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        boolean allowed = allowedDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        boolean publisherMatch = publisherTokens(candidate.publisher()).stream().anyMatch(host::contains);
        return new Candidate(
                bounded(candidate.title(), 180),
                uri.toASCIIString(),
                bounded(candidate.publisher(), 120),
                bounded(candidate.language(), 40),
                bounded(candidate.edition(), 120),
                host,
                allowed || publisherMatch);
    }

    private boolean looksLikePdf(URI uri) {
        return uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private Set<String> publisherTokens(String publisher) {
        if (publisher == null) return Set.of();
        return Arrays.stream(publisher.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").split(" "))
                .filter(value -> value.length() >= 4)
                .filter(value -> !Set.of("games", "game", "publishing", "publisher", "edition").contains(value))
                .collect(Collectors.toSet());
    }

    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String checked = value.strip();
        return checked.length() <= maximum ? checked : checked.substring(0, maximum);
    }

    public record Result(boolean configured, List<Candidate> candidates) {
        public Result {
            candidates = List.copyOf(candidates);
        }
    }

    public record Candidate(
            String title,
            String url,
            String publisher,
            String language,
            String edition,
            String sourceDomain,
            boolean officialDomainVerified) {}
}
