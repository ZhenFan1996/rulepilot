package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import java.net.IDN;
import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;
    private final Duration catalogProviderBudget;
    private final Duration sourceInspectionProviderBudget;
    private final Duration webSearchProviderBudget;
    private final Duration totalBudget;

    @Autowired
    public OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            CatalogGameSourceIdentityLookup sourceIdentities,
            OfficialRulebookCandidateFinder finder,
            GstoneRulebookCatalogLookup gstoneCatalog,
            OfficialRulebookSourceInspector sourceInspector,
            @Value("${rulepilot.rulebook-discovery.trusted-domains:}") String trustedDomains,
            @Value("${rulepilot.rulebook-discovery.catalog-provider-budget:PT6S}") Duration catalogProviderBudget,
            @Value("${rulepilot.rulebook-discovery.source-inspection-provider-budget:PT6S}")
                    Duration sourceInspectionProviderBudget,
            @Value("${rulepilot.rulebook-discovery.web-search-provider-budget:PT18S}") Duration webSearchProviderBudget,
            @Value("${rulepilot.rulebook-discovery.total-budget:PT30S}") Duration totalBudget) {
        this(
                catalog,
                sourceIdentities,
                finder,
                gstoneCatalog,
                sourceInspector,
                trustedDomains,
                catalogProviderBudget,
                sourceInspectionProviderBudget,
                webSearchProviderBudget,
                totalBudget,
                Clock.systemUTC());
    }

    public OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            CatalogGameSourceIdentityLookup sourceIdentities,
            OfficialRulebookCandidateFinder finder,
            GstoneRulebookCatalogLookup gstoneCatalog,
            OfficialRulebookSourceInspector sourceInspector,
            String trustedDomains) {
        this(
                catalog,
                sourceIdentities,
                finder,
                gstoneCatalog,
                sourceInspector,
                trustedDomains,
                Duration.ofSeconds(6),
                Duration.ofSeconds(6),
                Duration.ofSeconds(18),
                Duration.ofSeconds(30));
    }

    private OfficialRulebookDiscoveryService(
            CatalogGamePresentationLookup catalog,
            CatalogGameSourceIdentityLookup sourceIdentities,
            OfficialRulebookCandidateFinder finder,
            GstoneRulebookCatalogLookup gstoneCatalog,
            OfficialRulebookSourceInspector sourceInspector,
            String trustedDomains,
            Duration catalogProviderBudget,
            Duration sourceInspectionProviderBudget,
            Duration webSearchProviderBudget,
            Duration totalBudget,
            Clock clock) {
        this.catalog = catalog;
        this.sourceIdentities = sourceIdentities;
        this.finder = finder;
        this.gstoneCatalog = gstoneCatalog;
        this.sourceInspector = sourceInspector;
        this.clock = clock;
        this.catalogProviderBudget = checkedBudget(catalogProviderBudget, "catalog provider");
        this.sourceInspectionProviderBudget = checkedBudget(sourceInspectionProviderBudget, "source inspection provider");
        this.webSearchProviderBudget = checkedBudget(webSearchProviderBudget, "web search provider");
        this.totalBudget = checkedBudget(totalBudget, "total discovery");
        this.trustedDomains = Arrays.stream(trustedDomains.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Duration checkedBudget(Duration value, String label) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException(label + " budget must be between 1 ns and 2 minutes");
        }
        return value;
    }

    public Result discover(UUID editionId, String language) {
        var budget = new DiscoveryBudget(
                catalogProviderBudget,
                sourceInspectionProviderBudget,
                webSearchProviderBudget,
                totalBudget);
        boolean modelSearchConfigured = finder.configured();
        var game = catalog.findByEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("catalog edition does not exist or has no BGG metadata"));
        var discoveryIdentity = new DiscoveryIdentity(
                game.editionId(), game.gameName(), game.editionName(), game.language());
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
        List<OfficialRulebookCandidateFinder.Candidate> catalogDiscovered = budget
                .call(DiscoveryProvider.CATALOG, () -> gstoneCatalog.find(request))
                .orElse(List.of());
        List<Candidate> catalogCandidates = catalogDiscovered.stream()
                .map(candidate -> validate(candidate, identity.publishers()))
                .filter(java.util.Objects::nonNull)
                .toList();
        var allCandidates = new ArrayList<Candidate>();
        allCandidates.addAll(assessCandidates(catalogCandidates, request, budget));
        if (allCandidates.stream().anyMatch(candidate -> isImportableForLanguage(candidate, checkedLanguage))) {
            return completeResult(true, discoveryIdentity, allCandidates, budget);
        }
        if (!modelSearchConfigured) {
            budget.unavailable(DiscoveryProvider.WEB_SEARCH);
            return completeResult(!catalogDiscovered.isEmpty(), discoveryIdentity, allCandidates, budget);
        }

        var modelDiscovered = new ArrayList<>(budget
                .call(DiscoveryProvider.WEB_SEARCH, () -> finder.find(request))
                .orElse(List.of()));
        modelDiscovered.add(new OfficialRulebookCandidateFinder.Candidate(
                "BoardGameGeek Files",
                "https://boardgamegeek.com/files/thing/" + game.bggId(),
                "BoardGameGeek",
                checkedLanguage,
                game.editionName(),
                OfficialRulebookCandidateFinder.SourcePageHint.DOCUMENT_LISTING));
        List<Candidate> modelCandidates = modelDiscovered.stream()
                .map(candidate -> validate(candidate, identity.publishers()))
                .filter(java.util.Objects::nonNull)
                .toList();
        allCandidates.addAll(assessCandidates(modelCandidates, request, budget));
        if (allCandidates.stream().noneMatch(candidate -> isImportableForLanguage(candidate, checkedLanguage))
                && !budget.timedOut(DiscoveryProvider.WEB_SEARCH)) {
            List<OfficialRulebookCandidateFinder.Candidate> observedPages = rankedCandidates(allCandidates).stream()
                    .filter(candidate -> !isImportable(candidate))
                    .filter(candidate -> candidate.sourceType() != SourceType.PUBLIC_WEB)
                    .limit(6)
                    .map(this::finderCandidate)
                    .toList();
            List<Candidate> recovered = budget
                    .call(
                            DiscoveryProvider.WEB_SEARCH,
                            () -> finder.findAfterSourcePages(request, observedPages))
                    .orElse(List.of())
                    .stream()
                    .map(candidate -> validate(candidate, identity.publishers()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            allCandidates.addAll(assessCandidates(recovered, request, budget));
        }
        return completeResult(true, discoveryIdentity, allCandidates, budget);
    }

    private Result completeResult(
            boolean configured,
            DiscoveryIdentity identity,
            List<Candidate> candidates,
            DiscoveryBudget budget) {
        List<Candidate> ranked = rankedCandidates(candidates);
        return new Result(configured, identity, ranked, budget.summary(ranked.size()));
    }

    private List<Candidate> rankedCandidates(List<Candidate> allCandidates) {
        var uniqueCandidates = new LinkedHashMap<String, Candidate>();
        allCandidates.forEach(candidate ->
                uniqueCandidates.merge(candidate.url(), candidate, this::strongerAssessment));
        List<Candidate> candidates = uniqueCandidates.values().stream()
                .sorted(java.util.Comparator.comparingInt((Candidate candidate) -> capabilityPriority(candidate.capability()))
                .thenComparingInt(candidate -> sourcePriority(candidate.sourceType()))
                .thenComparingInt(candidate -> acquisitionPriority(candidate.acquisitionMode())))
                .limit(8)
                .toList();
        return candidates;
    }

    private List<Candidate> assessCandidates(
            List<Candidate> candidates,
            OfficialRulebookCandidateFinder.Request request,
            DiscoveryBudget budget) {
        var queue = new ArrayDeque<SourcePage>();
        var assessed = new ArrayList<Candidate>(candidates);
        candidates.stream()
                .filter(candidate -> !requiresBrowserHandoff(URI.create(candidate.url())))
                .sorted(Comparator.comparingInt((Candidate candidate) -> capabilityPriority(candidate.capability()))
                        .thenComparingInt(candidate -> sourcePriority(candidate.sourceType())))
                .forEach(candidate -> queue.add(new SourcePage(candidate, URI.create(candidate.url()), candidate.title(), 0)));
        Set<String> inspected = new HashSet<>();
        int inspections = 0;
        int confirmedDocuments = 0;
        while (!queue.isEmpty() && inspections < MAX_SOURCE_INSPECTIONS) {
            SourcePage page = queue.removeFirst();
            if (!inspected.add(page.url().toASCIIString())) continue;
            inspections++;
            Instant checkedAt = clock.instant();
            Optional<Optional<OfficialRulebookSourceInspector.Inspection>> inspectionAttempt = budget.call(
                    DiscoveryProvider.SOURCE_INSPECTION,
                    () -> sourceInspector.inspect(page.url()));
            Optional<OfficialRulebookSourceInspector.Inspection> inspectedSource =
                    inspectionAttempt.orElse(Optional.empty());
            if (inspectedSource.isEmpty()) {
                if (page.depth() == 0) {
                    assessed.add(withAssessment(
                            page.provenance(),
                            SourceCapability.UNVERIFIED_PAGE,
                            List.of(CapabilityEvidence.SOURCE_PROBE_UNAVAILABLE),
                            checkedAt));
                }
                if (budget.timedOut(DiscoveryProvider.SOURCE_INSPECTION)) break;
                continue;
            }
            var inspection = inspectedSource.orElseThrow();
            if (inspection.mediaType() == OfficialRulebookSourceInspector.MediaType.PDF) {
                if (confirmedDocuments++ < MAX_RESOLVED_DOWNLOADS) {
                    assessed.add(resolvedDownload(page, inspection.finalSource(), checkedAt));
                }
                continue;
            }
            if (inspection.mediaType() == OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY) {
                if (confirmedDocuments++ < MAX_RESOLVED_DOWNLOADS) {
                    assessed.add(resolvedImageGallery(page, inspection.finalSource(), checkedAt));
                }
                continue;
            }
            if (page.depth() == 0) {
                assessed.add(assessHtml(page.provenance(), inspection, checkedAt));
            }
            if (page.provenance().sourceType() == SourceType.PUBLIC_WEB || page.depth() >= 1) continue;
            inspection.links().stream()
                    .map(link -> new ScoredLink(link, linkScore(link, request)))
                    .filter(link -> link.score() >= MIN_SOURCE_LINK_SCORE)
                    .sorted(Comparator.comparingInt(ScoredLink::score).reversed()
                            .thenComparing(link -> link.link().target().toASCIIString()))
                    .limit(4)
                    .forEach(link -> {
                        URI target = link.link().target();
                        if (!inspected.contains(target.toASCIIString())) {
                            queue.addLast(new SourcePage(page.provenance(), target, link.link().label(), 1));
                        }
                    });
        }
        return List.copyOf(assessed);
    }

    private Candidate resolvedImageGallery(SourcePage page, URI target, Instant checkedAt) {
        String host = IDN.toASCII(target.getHost()).toLowerCase(Locale.ROOT);
        String title = page.label().isBlank() ? page.provenance().title() : page.label();
        LanguageResolution language = resolvedLanguage(page);
        return new Candidate(
                bounded(title, 180),
                target.toASCIIString(),
                page.provenance().publisher(),
                language.value(),
                page.provenance().edition(),
                host,
                page.provenance().officialDomainVerified(),
                language.verified(),
                page.provenance().sourceType(),
                AcquisitionMode.IMAGE_GALLERY,
                SourceCapability.CONTIGUOUS_RULE_PAGES,
                List.of(CapabilityEvidence.ORDERED_PAGE_SEQUENCE_CONFIRMED),
                checkedAt);
    }

    private Candidate resolvedDownload(SourcePage page, URI target, Instant checkedAt) {
        String host = IDN.toASCII(target.getHost()).toLowerCase(Locale.ROOT);
        String title = page.label().isBlank() ? page.provenance().title() : page.label();
        LanguageResolution language = resolvedLanguage(page);
        return new Candidate(
                bounded(title, 180),
                target.toASCIIString(),
                page.provenance().publisher(),
                language.value(),
                page.provenance().edition(),
                host,
                page.provenance().officialDomainVerified(),
                language.verified(),
                page.provenance().sourceType(),
                AcquisitionMode.DIRECT_PDF,
                SourceCapability.DIRECT_DOCUMENT,
                List.of(CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED),
                checkedAt);
    }

    private Candidate assessHtml(
            Candidate candidate,
            OfficialRulebookSourceInspector.Inspection inspection,
            Instant checkedAt) {
        Set<OfficialRulebookSourceInspector.PageSignal> signals = inspection.pageSignals();
        if (signals.contains(OfficialRulebookSourceInspector.PageSignal.LOGIN_REQUIRED)) {
            return withAssessment(
                    candidate,
                    SourceCapability.UNVERIFIED_PAGE,
                    List.of(CapabilityEvidence.ACCESS_REQUIRES_LOGIN),
                    checkedAt);
        }
        if (signals.contains(OfficialRulebookSourceInspector.PageSignal.EXPLICIT_EMPTY_DOCUMENT_COLLECTION)) {
            return withAssessment(
                    candidate,
                    SourceCapability.GAME_INFO_ONLY,
                    List.of(CapabilityEvidence.EXPLICIT_EMPTY_DOCUMENT_COLLECTION),
                    checkedAt);
        }
        if (signals.contains(OfficialRulebookSourceInspector.PageSignal.DOWNLOADABLE_DOCUMENT_LINKS)) {
            return withAssessment(
                    candidate,
                    SourceCapability.DOCUMENT_LISTING,
                    List.of(CapabilityEvidence.DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED),
                    checkedAt);
        }
        if (signals.contains(OfficialRulebookSourceInspector.PageSignal.STRUCTURED_GAME_INFORMATION)) {
            return withAssessment(
                    candidate,
                    SourceCapability.GAME_INFO_ONLY,
                    List.of(CapabilityEvidence.STRUCTURED_GAME_INFORMATION_OBSERVED),
                    checkedAt);
        }
        if (candidate.capability() == SourceCapability.DOCUMENT_LISTING
                || candidate.capability() == SourceCapability.GAME_INFO_ONLY) {
            return new Candidate(
                    candidate.title(),
                    candidate.url(),
                    candidate.publisher(),
                    candidate.language(),
                    candidate.edition(),
                    candidate.sourceDomain(),
                    candidate.officialDomainVerified(),
                    candidate.languageVerified(),
                    candidate.sourceType(),
                    AcquisitionMode.SOURCE_PAGE,
                    candidate.capability(),
                    candidate.capabilityEvidence(),
                    checkedAt);
        }
        return withAssessment(
                candidate,
                SourceCapability.UNVERIFIED_PAGE,
                List.of(CapabilityEvidence.HTML_PAGE_WITHOUT_DOCUMENT_CAPABILITY),
                checkedAt);
    }

    private LanguageResolution resolvedLanguage(SourcePage page) {
        String label = page.label();
        String words = normalizedWords(label);
        String compact = Normalizer.normalize(label == null ? "" : label, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (containsAnyWord(words, Set.of("chinese", "chinois", "zhongwen"))
                || compact.contains("简体中文") || compact.contains("繁體中文")
                || compact.contains("繁体中文") || compact.contains("中文")) {
            return new LanguageResolution("zh-CN", true);
        }
        if (containsAnyWord(words, Set.of("english", "anglais")) || compact.contains("英文")) {
            return new LanguageResolution("en", true);
        }
        if (containsAnyWord(words, Set.of("french", "francais")) || compact.contains("法文")) {
            return new LanguageResolution("fr", true);
        }
        if (containsAnyWord(words, Set.of("german", "allemand", "deutsch")) || compact.contains("德文")) {
            return new LanguageResolution("de", true);
        }
        if (containsAnyWord(words, Set.of("spanish", "espagnol", "espanol")) || compact.contains("西班牙文")) {
            return new LanguageResolution("es", true);
        }
        if (containsAnyWord(words, Set.of("italian", "italien", "italiano")) || compact.contains("意大利文")) {
            return new LanguageResolution("it", true);
        }
        if (containsAnyWord(words, Set.of("dutch", "neerlandais", "nederlands")) || compact.contains("荷兰文")) {
            return new LanguageResolution("nl", true);
        }
        if (containsAnyWord(words, Set.of("portuguese", "portugais", "portugues")) || compact.contains("葡萄牙文")) {
            return new LanguageResolution("pt", true);
        }
        if (matchesDomain(page.provenance().sourceDomain(), Set.of("gstonegames.com"))
                && compact.codePoints().anyMatch(this::isHan)) {
            return new LanguageResolution("zh-CN", true);
        }
        return new LanguageResolution(bounded(page.provenance().language(), 40), false);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private boolean containsAnyWord(String words, Set<String> terms) {
        return terms.stream().anyMatch(term -> containsWord(words, term));
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
                candidate.edition(),
                switch (candidate.capability()) {
                    case DOCUMENT_LISTING -> OfficialRulebookCandidateFinder.SourcePageHint.DOCUMENT_LISTING;
                    case GAME_INFO_ONLY -> OfficialRulebookCandidateFinder.SourcePageHint.GAME_INFORMATION;
                    default -> OfficialRulebookCandidateFinder.SourcePageHint.UNVERIFIED_PAGE;
                });
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
        boolean structurallyValidDocumentUrl = looksLikeDirectPdf(uri);
        AcquisitionMode acquisitionMode = AcquisitionMode.SOURCE_PAGE;
        if (isBggPageHost(host)
                && uri.getPath() != null
                && uri.getPath().startsWith("/file/download_redirect/")
                && !structurallyValidDocumentUrl) return null;
        SourceCapability initialCapability = switch (candidate.sourcePageHint()) {
            case DOCUMENT_LISTING -> SourceCapability.DOCUMENT_LISTING;
            case GAME_INFORMATION -> SourceCapability.GAME_INFO_ONLY;
            case UNVERIFIED_PAGE -> SourceCapability.UNVERIFIED_PAGE;
        };
        CapabilityEvidence initialEvidence = switch (candidate.sourcePageHint()) {
            case DOCUMENT_LISTING -> CapabilityEvidence.KNOWN_DOCUMENT_LISTING_ROUTE;
            case GAME_INFORMATION -> CapabilityEvidence.KNOWN_GAME_INFORMATION_ROUTE;
            case UNVERIFIED_PAGE -> CapabilityEvidence.CANDIDATE_ONLY;
        };
        return new Candidate(
                bounded(candidate.title(), 180),
                uri.toASCIIString(),
                bounded(candidate.publisher(), 120),
                normalizedCandidateLanguage(candidate.language()),
                bounded(candidate.edition(), 120),
                host,
                publisherMatch,
                false,
                sourceType,
                acquisitionMode,
                initialCapability,
                List.of(initialEvidence),
                clock.instant());
    }

    private String normalizedCandidateLanguage(String language) {
        if (language == null || language.isBlank()) return "";
        String checked = Normalizer.normalize(language.strip(), Normalizer.Form.NFKC);
        String words = normalizedWords(checked);
        if (Set.of("english", "anglais", "英文").contains(words)) return "en";
        if (Set.of("traditional chinese", "繁体中文", "繁體中文").contains(words)) return "zh-TW";
        if (Set.of("simplified chinese", "简体中文").contains(words)) return "zh-CN";
        if (Set.of("chinese", "chinois", "中文").contains(words)) return "zh";
        if (Set.of("french", "francais", "法文").contains(words)) return "fr";
        if (Set.of("german", "deutsch", "德文").contains(words)) return "de";
        if (Set.of("spanish", "espanol", "西班牙文").contains(words)) return "es";
        if (Set.of("italian", "italiano", "意大利文").contains(words)) return "it";
        if (Set.of("dutch", "nederlands", "荷兰文").contains(words)) return "nl";
        if (Set.of("portuguese", "portugues", "葡萄牙文").contains(words)) return "pt";
        String tag = checked.replace('_', '-');
        if (!tag.matches("(?i)[a-z]{2,3}(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?")) return "";
        String canonical = Locale.forLanguageTag(tag).toLanguageTag();
        return canonical.equalsIgnoreCase("und") ? "" : canonical;
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

    private int capabilityPriority(SourceCapability capability) {
        return switch (capability) {
            case DIRECT_DOCUMENT -> 0;
            case CONTIGUOUS_RULE_PAGES -> 1;
            case DOCUMENT_LISTING -> 2;
            case GAME_INFO_ONLY -> 3;
            case UNVERIFIED_PAGE -> 4;
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
        return candidate.capability() == SourceCapability.DIRECT_DOCUMENT
                        && candidate.acquisitionMode() == AcquisitionMode.DIRECT_PDF
                || candidate.capability() == SourceCapability.CONTIGUOUS_RULE_PAGES
                        && candidate.acquisitionMode() == AcquisitionMode.IMAGE_GALLERY;
    }

    private Candidate withAssessment(
            Candidate candidate,
            SourceCapability capability,
            List<CapabilityEvidence> evidence,
            Instant checkedAt) {
        AcquisitionMode mode = switch (capability) {
            case DIRECT_DOCUMENT -> AcquisitionMode.DIRECT_PDF;
            case CONTIGUOUS_RULE_PAGES -> AcquisitionMode.IMAGE_GALLERY;
            case DOCUMENT_LISTING, GAME_INFO_ONLY, UNVERIFIED_PAGE -> AcquisitionMode.SOURCE_PAGE;
        };
        return new Candidate(
                candidate.title(),
                candidate.url(),
                candidate.publisher(),
                candidate.language(),
                candidate.edition(),
                candidate.sourceDomain(),
                candidate.officialDomainVerified(),
                candidate.languageVerified(),
                candidate.sourceType(),
                mode,
                capability,
                evidence,
                checkedAt);
    }

    private Candidate strongerAssessment(Candidate first, Candidate second) {
        int firstStrength = assessmentStrength(first);
        int secondStrength = assessmentStrength(second);
        if (secondStrength != firstStrength) return secondStrength > firstStrength ? second : first;
        return capabilityPriority(second.capability()) < capabilityPriority(first.capability()) ? second : first;
    }

    private int assessmentStrength(Candidate candidate) {
        return candidate.capabilityEvidence().stream()
                .mapToInt(evidence -> switch (evidence) {
                    case DOCUMENT_RESPONSE_CONFIRMED, ORDERED_PAGE_SEQUENCE_CONFIRMED -> 100;
                    case EXPLICIT_EMPTY_DOCUMENT_COLLECTION, ACCESS_REQUIRES_LOGIN -> 90;
                    case DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED -> 80;
                    case STRUCTURED_GAME_INFORMATION_OBSERVED -> 70;
                    case HTML_PAGE_WITHOUT_DOCUMENT_CAPABILITY, SOURCE_PROBE_UNAVAILABLE -> 60;
                    case KNOWN_DOCUMENT_LISTING_ROUTE, KNOWN_GAME_INFORMATION_ROUTE -> 40;
                    case CANDIDATE_ONLY -> 10;
                })
                .max()
                .orElse(0);
    }

    private boolean isImportableForLanguage(Candidate candidate, String requestedLanguage) {
        if (!isImportable(candidate)) return false;
        String requested = primaryLanguage(requestedLanguage);
        if (requested.isBlank() || "und".equals(requested)) return true;
        return candidate.languageVerified() && requested.equals(primaryLanguage(candidate.language()));
    }

    private String primaryLanguage(String language) {
        String normalized = language == null ? "" : language.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
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

    public record Result(
            boolean configured,
            DiscoveryIdentity identity,
            List<Candidate> candidates,
            DiscoverySummary discovery) {
        public Result {
            if (identity == null) throw new IllegalArgumentException("rulebook discovery identity is required");
            if (discovery == null) throw new IllegalArgumentException("rulebook discovery summary is required");
            candidates = List.copyOf(candidates);
        }
    }

    public record DiscoverySummary(
            DiscoveryCompletion completion,
            long elapsedMs,
            long totalBudgetMs,
            List<ProviderProgress> providers) {
        public DiscoverySummary {
            if (completion == null
                    || elapsedMs < 0
                    || totalBudgetMs < 1
                    || providers == null) {
                throw new IllegalArgumentException("rulebook discovery summary is invalid");
            }
            providers = List.copyOf(providers);
        }
    }

    public record ProviderProgress(
            DiscoveryProvider provider,
            DiscoveryProviderState state,
            long elapsedMs) {
        public ProviderProgress {
            if (provider == null || state == null || elapsedMs < 0) {
                throw new IllegalArgumentException("rulebook discovery provider progress is invalid");
            }
        }
    }

    public enum DiscoveryCompletion {
        COMPLETE,
        PARTIAL,
        TIMED_OUT,
        FAILED
    }

    public enum DiscoveryProvider {
        CATALOG,
        SOURCE_INSPECTION,
        WEB_SEARCH
    }

    public enum DiscoveryProviderState {
        FINISHED,
        TIMED_OUT,
        FAILED,
        SKIPPED,
        UNAVAILABLE
    }

    public record DiscoveryIdentity(
            UUID editionId,
            String gameName,
            String editionName,
            String language) {
        public DiscoveryIdentity {
            if (editionId == null
                    || gameName == null
                    || gameName.isBlank()
                    || editionName == null
                    || editionName.isBlank()
                    || language == null
                    || language.isBlank()) {
                throw new IllegalArgumentException("rulebook discovery identity is invalid");
            }
            gameName = gameName.strip();
            editionName = editionName.strip();
            language = language.strip();
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
            boolean languageVerified,
            SourceType sourceType,
            AcquisitionMode acquisitionMode,
            SourceCapability capability,
            List<CapabilityEvidence> capabilityEvidence,
            Instant capabilityCheckedAt) {
        public Candidate {
            if (sourceType == null
                    || acquisitionMode == null
                    || capability == null
                    || capabilityEvidence == null
                    || capabilityEvidence.isEmpty()
                    || capabilityCheckedAt == null) {
                throw new IllegalArgumentException("rulebook source capability assessment is required");
            }
            AcquisitionMode requiredMode = switch (capability) {
                case DIRECT_DOCUMENT -> AcquisitionMode.DIRECT_PDF;
                case CONTIGUOUS_RULE_PAGES -> AcquisitionMode.IMAGE_GALLERY;
                case DOCUMENT_LISTING, GAME_INFO_ONLY, UNVERIFIED_PAGE -> AcquisitionMode.SOURCE_PAGE;
            };
            if (acquisitionMode != requiredMode) {
                throw new IllegalArgumentException("rulebook source capability and acquisition mode disagree");
            }
            capabilityEvidence = List.copyOf(capabilityEvidence);
        }

        public SourceAction nextAction() {
            return switch (capability) {
                case DIRECT_DOCUMENT -> SourceAction.IMPORT_DOCUMENT;
                case CONTIGUOUS_RULE_PAGES -> SourceAction.IMPORT_PAGE_SEQUENCE;
                case DOCUMENT_LISTING -> SourceAction.CONTINUE_ON_SOURCE;
                case GAME_INFO_ONLY -> SourceAction.USE_FOR_IDENTITY_ONLY;
                case UNVERIFIED_PAGE -> SourceAction.REVIEW_OR_UPLOAD;
            };
        }
    }

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

    public enum SourceCapability {
        DIRECT_DOCUMENT,
        CONTIGUOUS_RULE_PAGES,
        DOCUMENT_LISTING,
        GAME_INFO_ONLY,
        UNVERIFIED_PAGE
    }

    public enum CapabilityEvidence {
        DOCUMENT_RESPONSE_CONFIRMED,
        ORDERED_PAGE_SEQUENCE_CONFIRMED,
        DOWNLOADABLE_DOCUMENT_LINKS_OBSERVED,
        EXPLICIT_EMPTY_DOCUMENT_COLLECTION,
        STRUCTURED_GAME_INFORMATION_OBSERVED,
        ACCESS_REQUIRES_LOGIN,
        SOURCE_PROBE_UNAVAILABLE,
        HTML_PAGE_WITHOUT_DOCUMENT_CAPABILITY,
        KNOWN_DOCUMENT_LISTING_ROUTE,
        KNOWN_GAME_INFORMATION_ROUTE,
        CANDIDATE_ONLY
    }

    public enum SourceAction {
        IMPORT_DOCUMENT,
        IMPORT_PAGE_SEQUENCE,
        CONTINUE_ON_SOURCE,
        USE_FOR_IDENTITY_ONLY,
        REVIEW_OR_UPLOAD
    }

    private static final class DiscoveryBudget {

        private final long startedNanos = System.nanoTime();
        private final long totalBudgetNanos;
        private final EnumMap<DiscoveryProvider, Long> providerBudgets =
                new EnumMap<>(DiscoveryProvider.class);
        private final EnumMap<DiscoveryProvider, Long> providerElapsed =
                new EnumMap<>(DiscoveryProvider.class);
        private final EnumMap<DiscoveryProvider, DiscoveryProviderState> providerStates =
                new EnumMap<>(DiscoveryProvider.class);

        private DiscoveryBudget(
                Duration catalogBudget,
                Duration sourceInspectionBudget,
                Duration webSearchBudget,
                Duration totalBudget) {
            this.totalBudgetNanos = totalBudget.toNanos();
            providerBudgets.put(DiscoveryProvider.CATALOG, catalogBudget.toNanos());
            providerBudgets.put(DiscoveryProvider.SOURCE_INSPECTION, sourceInspectionBudget.toNanos());
            providerBudgets.put(DiscoveryProvider.WEB_SEARCH, webSearchBudget.toNanos());
            for (DiscoveryProvider provider : DiscoveryProvider.values()) {
                providerElapsed.put(provider, 0L);
                providerStates.put(provider, DiscoveryProviderState.SKIPPED);
            }
        }

        private <T> Optional<T> call(DiscoveryProvider provider, Supplier<T> action) {
            long remainingNanos = Math.min(totalRemainingNanos(), providerRemainingNanos(provider));
            if (remainingNanos <= 0) {
                providerStates.put(provider, DiscoveryProviderState.TIMED_OUT);
                return Optional.empty();
            }
            var task = new FutureTask<>(action::get);
            Thread thread = Thread.ofVirtual()
                    .name("rulebook-discovery-" + provider.name().toLowerCase(Locale.ROOT))
                    .unstarted(task);
            long callStartedNanos = System.nanoTime();
            thread.start();
            try {
                T value = task.get(remainingNanos, TimeUnit.NANOSECONDS);
                markFinished(provider);
                return Optional.ofNullable(value);
            } catch (TimeoutException exception) {
                task.cancel(true);
                providerStates.put(provider, DiscoveryProviderState.TIMED_OUT);
                return Optional.empty();
            } catch (InterruptedException exception) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                providerStates.put(provider, DiscoveryProviderState.FAILED);
                return Optional.empty();
            } catch (ExecutionException exception) {
                task.cancel(true);
                if (exception.getCause() instanceof Error error) throw error;
                providerStates.put(provider, DiscoveryProviderState.FAILED);
                return Optional.empty();
            } finally {
                long callElapsedNanos = Math.max(0, System.nanoTime() - callStartedNanos);
                providerElapsed.merge(provider, callElapsedNanos, DiscoveryBudget::saturatedAdd);
            }
        }

        private void unavailable(DiscoveryProvider provider) {
            if (providerStates.get(provider) == DiscoveryProviderState.SKIPPED) {
                providerStates.put(provider, DiscoveryProviderState.UNAVAILABLE);
            }
        }

        private boolean timedOut(DiscoveryProvider provider) {
            return providerStates.get(provider) == DiscoveryProviderState.TIMED_OUT;
        }

        private DiscoverySummary summary(int candidateCount) {
            boolean timedOut = providerStates.containsValue(DiscoveryProviderState.TIMED_OUT);
            boolean failed = providerStates.containsValue(DiscoveryProviderState.FAILED);
            DiscoveryCompletion completion = timedOut
                    ? candidateCount > 0 ? DiscoveryCompletion.PARTIAL : DiscoveryCompletion.TIMED_OUT
                    : failed
                            ? candidateCount > 0 ? DiscoveryCompletion.PARTIAL : DiscoveryCompletion.FAILED
                            : DiscoveryCompletion.COMPLETE;
            List<ProviderProgress> providers = Arrays.stream(DiscoveryProvider.values())
                    .map(provider -> new ProviderProgress(
                            provider,
                            providerStates.get(provider),
                            elapsedMillis(providerElapsed.get(provider))))
                    .toList();
            return new DiscoverySummary(
                    completion,
                    elapsedMillis(Math.max(0, System.nanoTime() - startedNanos)),
                    Math.max(1, Duration.ofNanos(totalBudgetNanos).toMillis()),
                    providers);
        }

        private void markFinished(DiscoveryProvider provider) {
            DiscoveryProviderState state = providerStates.get(provider);
            if (state != DiscoveryProviderState.TIMED_OUT && state != DiscoveryProviderState.FAILED) {
                providerStates.put(provider, DiscoveryProviderState.FINISHED);
            }
        }

        private long totalRemainingNanos() {
            return totalBudgetNanos - Math.max(0, System.nanoTime() - startedNanos);
        }

        private long providerRemainingNanos(DiscoveryProvider provider) {
            return providerBudgets.get(provider) - providerElapsed.get(provider);
        }

        private static long saturatedAdd(long left, long right) {
            if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
            return left + right;
        }

        private static long elapsedMillis(long nanos) {
            return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
        }
    }

    private record SourcePage(Candidate provenance, URI url, String label, int depth) {}

    private record LanguageResolution(String value, boolean verified) {}

    private record ScoredLink(OfficialRulebookSourceInspector.Link link, int score) {}
}
