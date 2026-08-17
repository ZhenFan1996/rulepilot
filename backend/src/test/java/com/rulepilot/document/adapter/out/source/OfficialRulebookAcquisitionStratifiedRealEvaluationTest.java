package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import com.rulepilot.document.adapter.out.pdf.PdfBoxPhotographedRulebookAssembler;
import com.rulepilot.document.application.MinioStorageProperties;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService;
import com.rulepilot.document.application.OfficialRulebookSourceInspector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-rulebook-acquisition-evaluation")
class OfficialRulebookAcquisitionStratifiedRealEvaluationTest {

    private static final long MAXIMUM_PDF_BYTES = 100L * 1024 * 1024;
    private static final URI GSTONE_APP_SEARCH =
            URI.create("https://www.gstonegames.com/app/search_game_by_content/");
    private static final URI ARK_NOVA_GAME =
            URI.create("https://www.gstonegames.com/game/info-29568.html");
    private static final URI ARK_NOVA_ENGLISH_RULEBOOK =
            URI.create("https://www.gstonegames.com/game/doc-3717.html");
    private static final URI ARK_NOVA_CHINESE_RULEBOOK =
            URI.create("https://www.gstonegames.com/game/doc-4505.html");
    private static final URI ONE_JOUR_ARK_NOVA =
            URI.create("https://www.1jour-1jeu.com/jeu-de-plateau/2022-ark-nova");
    private static final URI ONE_JOUR_ARK_NOVA_ENGLISH_PDF =
            URI.create("https://cdn.1j1ju.com/medias/59/24/4c-ark-nova-rulebook.pdf");
    private static final URI GSTONE_CHESS =
            URI.create("https://www.gstonegames.com/game/info-569.html");
    private static final URI BGG_ARK_NOVA_FILES =
            URI.create("https://boardgamegeek.com/files/thing/342942");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void resolvesAConfiguredChineseGstoneImageRulebookWithoutModelSearch() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_GSTONE_DISCOVERY_EVAL")));
        String gameName = requiredEnvironment("RULEBOOK_GSTONE_GAME_NAME");
        String expectedDocumentUrl = requiredEnvironment("RULEBOOK_GSTONE_EXPECTED_DOCUMENT_URL");
        UUID editionId = UUID.fromString("5b19fbee-8353-43de-bdb6-820ec564136b");
        CatalogGamePresentationLookup catalog = ignored -> Optional.of(new CatalogGamePresentationLookup.Presentation(
                editionId,
                gameName,
                "Base",
                "zh-CN",
                null,
                1,
                "https://example.test/game.jpg",
                1,
                5,
                120,
                10,
                "https://boardgamegeek.com/boardgame/1"));
        CatalogGameSourceIdentityLookup identity = ignored -> Optional.of(
                new CatalogGameSourceIdentityLookup.Identity(gameName, List.of(gameName), List.of()));
        OfficialRulebookCandidateFinder disabledFinder = new OfficialRulebookCandidateFinder() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public List<OfficialRulebookCandidateFinder.Candidate> find(
                    OfficialRulebookCandidateFinder.Request request) {
                throw new AssertionError("deterministic Gstone discovery must not call model search");
            }
        };
        var lookup = new HttpGstoneRulebookCatalogLookup(new OkHttpClient(), json, true);
        var inspector = new HttpOfficialRulebookSourceInspector(Duration.ofSeconds(20), 1024 * 1024);
        var discovery = new OfficialRulebookDiscoveryService(
                catalog, identity, disabledFinder, lookup, inspector, "");

        var candidate = discovery.discover(editionId, "zh-CN").candidates().stream()
                .filter(value -> value.url().equals(expectedDocumentUrl))
                .findFirst()
                .orElseThrow();

        assertThat(candidate.language()).isEqualTo("zh-CN");
        assertThat(candidate.languageVerified()).isTrue();
        assertThat(candidate.acquisitionMode())
                .isEqualTo(OfficialRulebookDiscoveryService.AcquisitionMode.IMAGE_GALLERY);
        assertThat(candidate.capability())
                .isEqualTo(OfficialRulebookDiscoveryService.SourceCapability.CONTIGUOUS_RULE_PAGES);
    }

    @Test
    void writesASeparatedRealSourceOutcomeReportWithoutAnOverallSuccessClaim() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULEBOOK_STRATIFIED_EVAL")));
        var http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(30))
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        var lookup = new HttpGstoneRulebookCatalogLookup(http, json, true);
        var inspector = new HttpOfficialRulebookSourceInspector(Duration.ofSeconds(20), 1024 * 1024);
        var cases = new ArrayList<Map<String, Object>>();

        JsonNode rawArkSearch = publicGstoneSearch(http, "方舟动物园");
        long nearbyEditions = java.util.stream.StreamSupport.stream(
                        rawArkSearch.path("data").path("game_list").spliterator(), false)
                .map(game -> game.path("sch_name").asText(""))
                .filter(name -> name.startsWith("方舟动物园") && !name.equals("方舟动物园"))
                .count();
        assertThat(nearbyEditions).isPositive();
        var exactArk = lookup.find(request("方舟动物园", "zh-CN"));
        assertThat(exactArk)
                .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                .containsExactly(ARK_NOVA_GAME.toASCIIString());
        cases.add(outcome(
                "discovery-gstone-app-exact-name",
                "DISCOVERY",
                "SUCCESS",
                Map.of("exactCandidates", exactArk.size(), "nearbyEditionsRejected", nearbyEditions)));
        cases.add(outcome(
                "edition-control-gstone-app",
                "WRONG_EDITION_OR_LANGUAGE",
                "WRONG_EDITION_REJECTED_BEFORE_IMPORT",
                Map.of("nearbyEditionCandidatesObserved", nearbyEditions, "selectedGameId", 29568)));

        var gamePage = inspector.inspect(ARK_NOVA_GAME).orElseThrow();
        Set<URI> documentLinks = gamePage.links().stream()
                .map(OfficialRulebookSourceInspector.Link::target)
                .filter(target -> target.getPath().startsWith("/game/doc-"))
                .collect(Collectors.toSet());
        assertThat(documentLinks).contains(ARK_NOVA_ENGLISH_RULEBOOK, ARK_NOVA_CHINESE_RULEBOOK);
        assertThat(inspector.inspect(ARK_NOVA_CHINESE_RULEBOOK))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::mediaType)
                .isEqualTo(OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY);
        cases.add(outcome(
                "source-page-gstone-chinese-rulebook",
                "SOURCE_PAGE_RESOLUTION",
                "IMAGE_GALLERY_RESOLVED",
                Map.of("documentLinks", documentLinks.size(), "requestedLanguage", "zh-CN")));

        UUID editionId = UUID.fromString("5b19fbee-8353-43de-bdb6-820ec564136b");
        CatalogGamePresentationLookup catalog = ignored -> Optional.of(new CatalogGamePresentationLookup.Presentation(
                editionId,
                "Ark Nova",
                "Base",
                "en",
                2021,
                342942,
                "https://example.test/ark-nova.jpg",
                1,
                4,
                150,
                10,
                "https://boardgamegeek.com/boardgame/342942"));
        CatalogGameSourceIdentityLookup identity = ignored -> Optional.of(new CatalogGameSourceIdentityLookup.Identity(
                "Ark Nova", List.of("Ark Nova", "方舟动物园"), List.of("Feuerland Spiele")));
        OfficialRulebookCandidateFinder disabledFinder = new OfficialRulebookCandidateFinder() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public List<OfficialRulebookCandidateFinder.Candidate> find(
                    OfficialRulebookCandidateFinder.Request request) {
                throw new AssertionError("model search must not run in the deterministic Gstone evaluation");
            }
        };
        var discovery = new OfficialRulebookDiscoveryService(
                catalog, identity, disabledFinder, lookup, inspector, "");
        var discoveredDocuments = discovery.discover(editionId, "zh-CN").candidates();
        assertThat(discoveredDocuments)
                .filteredOn(candidate -> candidate.url().equals(ARK_NOVA_ENGLISH_RULEBOOK.toASCIIString()))
                .singleElement()
                .extracting(OfficialRulebookDiscoveryService.Candidate::language)
                .isEqualTo("en");
        assertThat(discoveredDocuments)
                .filteredOn(candidate -> candidate.url().equals(ARK_NOVA_CHINESE_RULEBOOK.toASCIIString()))
                .singleElement()
                .extracting(OfficialRulebookDiscoveryService.Candidate::language)
                .isEqualTo("zh-CN");
        assertThat(discoveredDocuments)
                .noneMatch(candidate -> candidate.url().endsWith("/game/doc-4785.html")
                        || candidate.url().endsWith("/game/doc-4786.html"));
        cases.add(outcome(
                "language-control-gstone-documents",
                "WRONG_EDITION_OR_LANGUAGE",
                "LANGUAGE_LABELED_FOR_EXPLICIT_REVIEW",
                Map.of(
                        "englishDocumentLanguage", "en",
                        "chineseDocumentLanguage", "zh-CN",
                        "playerAidAndFaqExcluded", true)));

        var fetcher = new HttpOfficialRulebookSourceFetcher(
                new MinioStorageProperties(
                        "http://127.0.0.1:9000",
                        "evaluation-access",
                        "evaluation-secret",
                        "rulepilot-evaluation",
                        MAXIMUM_PDF_BYTES),
                new PdfBoxPhotographedRulebookAssembler(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(90),
                Duration.ofMinutes(10),
                1024 * 1024);
        var fetched = fetcher.fetch(ARK_NOVA_CHINESE_RULEBOOK);
        int pageCount;
        try (var pdf = Loader.loadPDF(fetched.content())) {
            pageCount = pdf.getNumberOfPages();
        }
        assertThat(pageCount).isEqualTo(20);
        cases.add(outcome(
                "private-import-gstone-page-images",
                "PDF_DOWNLOAD",
                "SUCCESS_IMAGE_GALLERY_TO_PDF",
                Map.of("pages", pageCount, "bytes", fetched.content().length)));

        var oneJour = inspector.inspect(ONE_JOUR_ARK_NOVA).orElseThrow();
        assertThat(oneJour.links())
                .extracting(OfficialRulebookSourceInspector.Link::target)
                .contains(ONE_JOUR_ARK_NOVA_ENGLISH_PDF);
        assertThat(inspector.inspect(ONE_JOUR_ARK_NOVA_ENGLISH_PDF))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::mediaType)
                .isEqualTo(OfficialRulebookSourceInspector.MediaType.PDF);
        cases.add(outcome(
                "source-page-onejour-ark-nova",
                "SOURCE_PAGE_RESOLUTION",
                "DIRECT_PDF_RESOLVED",
                Map.of("downloaderUsed", "RulePilot bounded inspector", "mcpDownloaderUsed", false)));

        var chessCandidates = lookup.find(request("Chess", "en"));
        assertThat(chessCandidates)
                .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                .containsExactly(GSTONE_CHESS.toASCIIString());
        var chessPage = inspector.inspect(GSTONE_CHESS).orElseThrow();
        assertThat(chessPage.links())
                .noneMatch(link -> link.target().getPath().startsWith("/game/doc-"));
        cases.add(outcome(
                "no-file-gstone-chess",
                "NO_FILE",
                "SOURCE_PAGE_HAS_NO_RULEBOOK_DOCUMENT",
                Map.of("gameId", 569)));

        int bggStatus;
        String bggMitigation;
        try (var response = http.newCall(new Request.Builder()
                        .url(BGG_ARK_NOVA_FILES.toASCIIString())
                        .header("Accept", "text/html")
                        .header("User-Agent", "RulePilot/0.1 source-evaluation")
                        .build())
                .execute()) {
            bggStatus = response.code();
            bggMitigation = response.header("cf-mitigated", "");
        }
        assertThat(bggStatus).isEqualTo(403);
        assertThat(bggMitigation).isEqualTo("challenge");
        cases.add(outcome(
                "anti-bot-bgg-files",
                "ANTI_BOT",
                "CLOUDFLARE_CHALLENGE_OBSERVED",
                Map.of("httpStatus", bggStatus, "mitigation", bggMitigation)));
        assertThatThrownBy(() -> inspector.inspect(BGG_ARK_NOVA_FILES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("browser handoff");
        cases.add(outcome(
                "browser-handoff-bgg-files",
                "BROWSER_HANDOFF",
                "SUPPORTED_WITH_CONTEXT_PRESERVED",
                Map.of("backendChallengeBypassAttempted", false)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("cases", cases);
        report.put("metrics", Map.of(
                "exactDiscovery", Map.of("attempted", 1, "succeeded", 1),
                "privatePdfBuild", Map.of("attempted", 1, "succeeded", 1),
                "sourcePageResolution", Map.of("attempted", 2, "succeeded", 2),
                "browserHandoff", Map.of("attempted", 1, "succeeded", 1),
                "antiBotClassification", Map.of("attempted", 1, "succeeded", 1),
                "noFileClassification", Map.of("attempted", 1, "succeeded", 1)));
        report.put("controls", Map.of(
                "overallSuccessPercentageClaimed", false,
                "rawPdfStored", false,
                "rawRulebookTextStored", false,
                "credentialsUsed", false,
                "bggChallengeBypassed", false));

        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path output = root.resolve(".local/agent-evaluation/rulebook-acquisition-stratified-real.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    private JsonNode publicGstoneSearch(OkHttpClient http, String name) throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("content", name, "page", 1));
        try (var response = http.newCall(new Request.Builder()
                        .url(GSTONE_APP_SEARCH.toASCIIString())
                        .header("Accept", "application/json,text/plain;q=0.9")
                        .header("User-Agent", "RulePilot/0.1 source-evaluation")
                        .post(RequestBody.create(body, JSON))
                        .build())
                .execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            byte[] content = response.body().byteStream().readNBytes(256 * 1024 + 1);
            assertThat(content.length).isLessThanOrEqualTo(256 * 1024);
            JsonNode root = json.readTree(content);
            assertThat(root.path("status").asInt()).isEqualTo(200);
            return root;
        }
    }

    private OfficialRulebookCandidateFinder.Request request(String gameName, String language) {
        return new OfficialRulebookCandidateFinder.Request(
                1, gameName, "base", null, language, List.of(gameName), List.of(), List.of());
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private Map<String, Object> outcome(
            String caseId, String stratum, String outcome, Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("stratum", stratum);
        result.put("outcome", outcome);
        result.put("evidence", evidence);
        return result;
    }
}
