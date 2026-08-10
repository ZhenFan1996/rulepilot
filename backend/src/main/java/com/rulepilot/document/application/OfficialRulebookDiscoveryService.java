package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import java.net.IDN;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class OfficialRulebookDiscoveryService {

    private static final Set<String> COMMUNITY_DOMAINS =
            Set.of(
                    "boardgamegeek.com",
                    "geekdo.com",
                    "geekdo-images.com",
                    "geekdo-static.com",
                    "gstonegames.com",
                    "1jour-1jeu.com",
                    "1j1ju.com");
    private static final Pattern BGG_DOWNLOAD_PATH = Pattern.compile(
            "^/file/download_redirect/[a-fA-F0-9]{48}/[^/]{1,512}$");
    private static final int MAX_SOURCE_INSPECTIONS = 4;
    private static final int MAX_RESOLVED_DOWNLOADS = 4;
    private static final int MIN_SOURCE_LINK_SCORE = 3;
    private static final Set<String> RULE_TERMS = Set.of(
            "rules", "rulebook", "manual", "instructions", "regles", "regeln", "spielanleitung",
            "regolamento", "reglas", "pravila", "规则", "規則", "说明书", "說明書");
    private static final Set<String> DOWNLOAD_TERMS = Set.of(
            "download", "downloads", "telecharger", "herunterladen", "descargar", "scarica", "baixar", "下载", "下載");
    private static final Set<String> DOCUMENT_TERMS = Set.of(
            "doc", "document", "documents", "files", "resources", "library", "文档", "文件", "资料", "資源");
    private static final Set<String> NON_RULE_DOCUMENT_TERMS = Set.of(
            "privacy", "legal", "terms", "warranty", "catalog", "catalogue", "brochure", "press kit",
            "faq", "errata", "summary", "quick reference", "player aid", "score sheet", "scoresheet",
            "glossary", "icon overview", "icon glossary", "terminology", "术语表", "图标概览", "詞彙表");

    private final CatalogGamePresentationLookup catalog;
    private final CatalogGameSourceIdentityLookup sourceIdentities;
    private final OfficialRulebookCandidateFinder finder;
    private final GstoneRulebookCatalogLookup gstoneCatalog;
    private final OfficialRulebookSourceInspector sourceInspector;
    private final Set<String> trustedDomains;

    public OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            CatalogGameSourceIdentityLookup sourceIdentities,
            OfficialRulebookCandidateFinder finder,
            GstoneRulebookCatalogLookup gstoneCatalog,
            OfficialRulebookSourceInspector sourceInspector,
            @Value("${rulepilot.rulebook-discovery.trusted-domains:}") String trustedDomains) {
        this.catalog = catalog;
        this.sourceIdentities = sourceIdentities;
        this.finder = finder;
        this.gstoneCatalog = gstoneCatalog;
        this.sourceInspector = sourceInspector;
        this.trustedDomains = Arrays.stream(trustedDomains.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Result discover(UUID editionId, String language) {
        boolean modelSearchConfigured = finder.configured();
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
        var discovered = new ArrayList<>(gstoneCatalog.find(request));
        if (!modelSearchConfigured && discovered.isEmpty()) return new Result(false, List.of());
        if (modelSearchConfigured) {
            discovered.addAll(finder.find(request));
            discovered.add(new OfficialRulebookCandidateFinder.Candidate(
                    "BoardGameGeek Files",
                    "https://boardgamegeek.com/files/thing/" + game.bggId(),
                    "BoardGameGeek",
                    checkedLanguage,
                    game.editionName()));
        }
        List<Candidate> initialCandidates = discovered.stream()
                .map(candidate -> validate(candidate, identity.publishers()))
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Candidate> resolvedCandidates = resolveSourcePages(initialCandidates, request);
        var allCandidates = new ArrayList<Candidate>();
        allCandidates.addAll(initialCandidates);
        allCandidates.addAll(resolvedCandidates);
        if (modelSearchConfigured && allCandidates.stream().noneMatch(this::isImportable)) {
            List<OfficialRulebookCandidateFinder.Candidate> observedPages = initialCandidates.stream()
                    .filter(candidate -> candidate.acquisitionMode() == AcquisitionMode.SOURCE_PAGE)
                    .filter(candidate -> candidate.sourceType() != SourceType.PUBLIC_WEB)
                    .limit(6)
                    .map(this::finderCandidate)
                    .toList();
            finder.findAfterSourcePages(request, observedPages).stream()
                    .map(candidate -> validate(candidate, identity.publishers()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(allCandidates::add);
        }
        var uniqueCandidates = new LinkedHashMap<String, Candidate>();
        allCandidates.stream()
                .filter(candidate -> candidate.sourceType() != SourceType.PUBLIC_WEB
                        || isImportable(candidate))
                .forEach(candidate -> uniqueCandidates.putIfAbsent(candidate.url(), candidate));
        List<Candidate> candidates = uniqueCandidates.values().stream()
                .sorted(java.util.Comparator.comparingInt((Candidate candidate) -> sourcePriority(candidate.sourceType()))
                        .thenComparingInt(candidate -> acquisitionPriority(candidate.acquisitionMode())))
                .limit(8)
                .toList();
        return new Result(true, candidates);
    }

    private List<Candidate> resolveSourcePages(
            List<Candidate> candidates, OfficialRulebookCandidateFinder.Request request) {
        if (candidates.stream().anyMatch(candidate -> candidate.acquisitionMode() == AcquisitionMode.DIRECT_PDF
                && (candidate.sourceType() == SourceType.PUBLISHER
                        || candidate.sourceType() == SourceType.TRUSTED_REPOSITORY))) {
            return List.of();
        }
        var queue = new ArrayDeque<SourcePage>();
        candidates.stream()
                .filter(candidate -> candidate.acquisitionMode() == AcquisitionMode.SOURCE_PAGE)
                .filter(candidate -> !requiresBrowserHandoff(URI.create(candidate.url())))
                .sorted(Comparator.comparingInt(candidate -> sourcePriority(candidate.sourceType())))
                .limit(3)
                .forEach(candidate -> queue.add(new SourcePage(candidate, URI.create(candidate.url()), candidate.title(), 0)));
        Set<String> inspected = new HashSet<>();
        var resolved = new ArrayList<Candidate>();
        int inspections = 0;
        while (!queue.isEmpty()
                && inspections < MAX_SOURCE_INSPECTIONS
                && resolved.size() < MAX_RESOLVED_DOWNLOADS) {
            SourcePage page = queue.removeFirst();
            if (!inspected.add(page.url().toASCIIString())) continue;
            inspections++;
            Optional<OfficialRulebookSourceInspector.Inspection> inspectedSource = sourceInspector.inspect(page.url());
            if (inspectedSource.isEmpty()) continue;
            var inspection = inspectedSource.orElseThrow();
            if (inspection.mediaType() == OfficialRulebookSourceInspector.MediaType.PDF) {
                resolved.add(resolvedDownload(page, inspection.finalSource()));
                continue;
            }
            if (inspection.mediaType() == OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY) {
                resolved.add(resolvedImageGallery(page, inspection.finalSource()));
                continue;
            }
            if (page.provenance().sourceType() == SourceType.PUBLIC_WEB || page.depth() >= 1) continue;
            inspection.links().stream()
                    .map(link -> new ScoredLink(link, linkScore(link, request)))
                    .filter(link -> link.score() >= MIN_SOURCE_LINK_SCORE)
                    .sorted(Comparator.comparingInt(ScoredLink::score).reversed()
                            .thenComparing(link -> link.link().target().toASCIIString()))
                    .limit(4)
                    .forEach(link -> {
                        if (resolved.size() >= MAX_RESOLVED_DOWNLOADS) return;
                        URI target = link.link().target();
                        if (looksLikeDirectPdf(target)) {
                            if (resolved.stream().noneMatch(candidate -> candidate.url().equals(target.toASCIIString()))) {
                                resolved.add(resolvedDownload(
                                        new SourcePage(page.provenance(), target, link.link().label(), 1), target));
                            }
                        } else if (!inspected.contains(target.toASCIIString())) {
                            queue.addLast(new SourcePage(page.provenance(), target, link.link().label(), 1));
                        }
                    });
        }
        return List.copyOf(resolved);
    }

    private Candidate resolvedImageGallery(SourcePage page, URI target) {
        String host = IDN.toASCII(target.getHost()).toLowerCase(Locale.ROOT);
        String title = page.label().isBlank() ? page.provenance().title() : page.label();
        return new Candidate(
                bounded(title, 180),
                target.toASCIIString(),
                page.provenance().publisher(),
                resolvedLanguage(page),
                page.provenance().edition(),
                host,
                page.provenance().officialDomainVerified(),
                page.provenance().sourceType(),
                AcquisitionMode.IMAGE_GALLERY);
    }

    private Candidate resolvedDownload(SourcePage page, URI target) {
        String host = IDN.toASCII(target.getHost()).toLowerCase(Locale.ROOT);
        String title = page.label().isBlank() ? page.provenance().title() : page.label();
        return new Candidate(
                bounded(title, 180),
                target.toASCIIString(),
                page.provenance().publisher(),
                resolvedLanguage(page),
                page.provenance().edition(),
                host,
                page.provenance().officialDomainVerified(),
                page.provenance().sourceType(),
                AcquisitionMode.DIRECT_PDF);
    }

    private String resolvedLanguage(SourcePage page) {
        String label = page.label();
        String words = normalizedWords(label);
        if (containsAnyWord(words, Set.of("chinese", "chinois", "zhongwen"))) return "zh-CN";
        if (containsAnyWord(words, Set.of("english", "anglais", "rulebook", "rules", "instructions"))) return "en";
        if (containsAnyWord(words, Set.of("french", "francais", "regles"))) return "fr";
        if (containsAnyWord(words, Set.of("german", "allemand", "deutsch", "regeln", "spielanleitung"))) return "de";
        if (containsAnyWord(words, Set.of("spanish", "espagnol", "espanol", "reglas"))) return "es";
        if (containsAnyWord(words, Set.of("italian", "italien", "italiano", "regolamento"))) return "it";
        if (containsAnyWord(words, Set.of("dutch", "neerlandais", "nederlands", "spelregels"))) return "nl";
        if (containsAnyWord(words, Set.of("portuguese", "portugais", "portugues", "regras"))) return "pt";
        if (label != null && label.codePoints().anyMatch(this::isHan)) return "zh-CN";
        return page.provenance().language();
    }

    private boolean containsAnyWord(String words, Set<String> terms) {
        return terms.stream().anyMatch(term -> containsWord(words, term));
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private int linkScore(
            OfficialRulebookSourceInspector.Link link, OfficialRulebookCandidateFinder.Request request) {
        URI target = link.target();
        String pathAndLabel = normalizedWords(
                (target.getPath() == null ? "" : target.getPath()) + " "
                        + (target.getQuery() == null ? "" : target.getQuery()) + " "
                        + link.label());
        if (NON_RULE_DOCUMENT_TERMS.stream().anyMatch(pathAndLabel::contains)) return -20;
        int score = looksLikeDirectPdf(target) ? 6 : 0;
        if (RULE_TERMS.stream().anyMatch(pathAndLabel::contains)) score += 5;
        if (DOWNLOAD_TERMS.stream().anyMatch(pathAndLabel::contains)) score += 3;
        if (DOCUMENT_TERMS.stream().anyMatch(term -> containsWord(pathAndLabel, term))) score += 2;
        if (titleTokens(request).stream().anyMatch(pathAndLabel::contains)) score += 4;
        return score;
    }

    private boolean containsWord(String words, String term) {
        return (" " + words + " ").contains(" " + term + " ");
    }

    private Set<String> titleTokens(OfficialRulebookCandidateFinder.Request request) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(request.gameName()), request.officialNames().stream())
                .flatMap(value -> Arrays.stream(normalizedWords(value).split(" ")))
                .filter(token -> token.codePointCount(0, token.length()) >= (token.matches("[a-z0-9]+") ? 4 : 2))
                .filter(token -> !Set.of("board", "game", "edition", "official", "rules", "rulebook").contains(token))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizedWords(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private OfficialRulebookCandidateFinder.Candidate finderCandidate(Candidate candidate) {
        return new OfficialRulebookCandidateFinder.Candidate(
                candidate.title(),
                candidate.url(),
                candidate.publisher(),
                candidate.language(),
                candidate.edition());
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
        if (isBggPageHost(host)
                && uri.getPath() != null
                && uri.getPath().startsWith("/file/download_redirect/")
                && acquisitionMode != AcquisitionMode.DIRECT_PDF) return null;
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
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (isBggPageHost(host)) {
            return uri.getQuery() == null && BGG_DOWNLOAD_PATH.matcher(uri.getPath() == null ? "" : uri.getPath()).matches();
        }
        return path.endsWith(".pdf") || query.contains(".pdf");
    }

    private boolean isBggPageHost(String host) {
        return host.equals("boardgamegeek.com") || host.equals("www.boardgamegeek.com");
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
        return switch (acquisitionMode) {
            case DIRECT_PDF -> 0;
            case IMAGE_GALLERY -> 1;
            case SOURCE_PAGE -> 2;
        };
    }

    private boolean isImportable(Candidate candidate) {
        return candidate.acquisitionMode() == AcquisitionMode.DIRECT_PDF
                || candidate.acquisitionMode() == AcquisitionMode.IMAGE_GALLERY;
    }

    private boolean requiresBrowserHandoff(URI source) {
        String host = source.getHost() == null ? "" : source.getHost().toLowerCase(Locale.ROOT);
        return isBggPageHost(host) || host.equals("geekdo.com") || host.endsWith(".geekdo.com");
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
        IMAGE_GALLERY,
        SOURCE_PAGE
    }

    private record SourcePage(Candidate provenance, URI url, String label, int depth) {}

    private record ScoredLink(OfficialRulebookSourceInspector.Link link, int score) {}
}
