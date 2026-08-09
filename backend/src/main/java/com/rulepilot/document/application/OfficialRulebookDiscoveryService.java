package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import java.net.IDN;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

    private static final Set<String> COMMUNITY_DOMAINS =
            Set.of("boardgamegeek.com", "geekdo.com", "geekdo-images.com", "geekdo-static.com");

    private final CatalogGamePresentationLookup catalog;
    private final CatalogGameSourceIdentityLookup sourceIdentities;
    private final OfficialRulebookCandidateFinder finder;
    private final Set<String> trustedDomains;

    public OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            CatalogGameSourceIdentityLookup sourceIdentities,
            OfficialRulebookCandidateFinder finder,
            @Value("${rulepilot.rulebook-discovery.trusted-domains:}") String trustedDomains) {
        this.catalog = catalog;
        this.sourceIdentities = sourceIdentities;
        this.finder = finder;
        this.trustedDomains = Arrays.stream(trustedDomains.split(","))
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
        var identity = sourceIdentities.findByBggId(game.bggId())
                .orElse(new CatalogGameSourceIdentityLookup.Identity(game.gameName(), List.of(game.gameName()), List.of()));
        var request = new OfficialRulebookCandidateFinder.Request(
                game.bggId(),
                game.gameName(),
                game.editionName(),
                game.publicationYear(),
                checkedLanguage,
                identity.officialNames(),
                identity.publishers(),
                trustedDomains.stream().sorted().toList());
        var discovered = new java.util.ArrayList<>(finder.find(request));
        discovered.add(new OfficialRulebookCandidateFinder.Candidate(
                "BoardGameGeek Files",
                "https://boardgamegeek.com/files/thing/" + game.bggId(),
                "BoardGameGeek",
                checkedLanguage,
                game.editionName()));
        var uniqueCandidates = new LinkedHashMap<String, Candidate>();
        discovered.stream()
                .map(candidate -> validate(candidate, identity.publishers()))
                .filter(java.util.Objects::nonNull)
                .forEach(candidate -> uniqueCandidates.putIfAbsent(candidate.url(), candidate));
        List<Candidate> candidates = uniqueCandidates.values().stream()
                .sorted(java.util.Comparator.comparingInt((Candidate candidate) -> sourcePriority(candidate.sourceType()))
                        .thenComparingInt(candidate -> acquisitionPriority(candidate.acquisitionMode())))
                .limit(8)
                .toList();
        return new Result(true, candidates);
    }

    private Candidate validate(
            OfficialRulebookCandidateFinder.Candidate candidate, List<String> verifiedPublishers) {
        if (candidate == null || candidate.url() == null || candidate.title() == null) return null;
        URI uri;
        try {
            uri = URI.create(candidate.url().strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            return null;
        }
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        boolean trusted = trustedDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        boolean publisherMatch = verifiedPublishers.stream()
                .flatMap(publisher -> publisherTokens(publisher).stream())
                .anyMatch(host::contains);
        SourceType sourceType = publisherMatch
                ? SourceType.PUBLISHER
                : trusted
                        ? SourceType.TRUSTED_REPOSITORY
                        : matchesDomain(host, COMMUNITY_DOMAINS)
                                ? SourceType.COMMUNITY_PLATFORM
                                : SourceType.PUBLIC_WEB;
        AcquisitionMode acquisitionMode = looksLikeDirectPdf(uri) ? AcquisitionMode.DIRECT_PDF : AcquisitionMode.SOURCE_PAGE;
        if (sourceType == SourceType.PUBLIC_WEB && acquisitionMode == AcquisitionMode.SOURCE_PAGE) return null;
        return new Candidate(
                bounded(candidate.title(), 180),
                uri.toASCIIString(),
                bounded(candidate.publisher(), 120),
                bounded(candidate.language(), 40),
                bounded(candidate.edition(), 120),
                host,
                publisherMatch,
                sourceType,
                acquisitionMode);
    }

    private boolean looksLikeDirectPdf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
        return path.endsWith(".pdf") || query.contains(".pdf") || path.contains("/file/download_redirect/");
    }

    private boolean matchesDomain(String host, Set<String> domains) {
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private int sourcePriority(SourceType sourceType) {
        return switch (sourceType) {
            case PUBLISHER -> 0;
            case TRUSTED_REPOSITORY -> 1;
            case COMMUNITY_PLATFORM -> 2;
            case PUBLIC_WEB -> 3;
        };
    }

    private int acquisitionPriority(AcquisitionMode acquisitionMode) {
        return acquisitionMode == AcquisitionMode.DIRECT_PDF ? 0 : 1;
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
            boolean officialDomainVerified,
            SourceType sourceType,
            AcquisitionMode acquisitionMode) {}

    public enum SourceType {
        PUBLISHER,
        TRUSTED_REPOSITORY,
        COMMUNITY_PLATFORM,
        PUBLIC_WEB
    }

    public enum AcquisitionMode {
        DIRECT_PDF,
        SOURCE_PAGE
    }
}
