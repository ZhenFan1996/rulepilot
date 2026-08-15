package com.rulepilot.document.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.document.application.GstoneRulebookCatalogLookup;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class HttpGstoneRulebookCatalogLookup implements GstoneRulebookCatalogLookup {

    private static final int MAX_HTML_BYTES = 1024 * 1024;
    private static final int MAX_SEARCH_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_SEARCH_NAMES = 3;
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_GAME_ID = 9_999_999;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern GAME_PATH = Pattern.compile("^/game/info-[0-9]+\\.html$");
    private static final URI PUBLIC_APP_SEARCH =
            URI.create("https://www.gstonegames.com/app/search_game_by_content/");
    private static final List<URI> PUBLIC_CATALOG_PAGES = List.of(
            URI.create("https://www.gstonegames.com/"),
            URI.create("https://www.gstonegames.com/ranking/"));
    private static final Dns PUBLIC_DNS = hostname -> {
        List<java.net.InetAddress> publicAddresses = Dns.SYSTEM.lookup(hostname).stream()
                .filter(OfficialRulebookNetworkAddressPolicy::isPublic)
                .toList();
        if (publicAddresses.isEmpty()) {
            throw new UnknownHostException("Gstone catalog did not resolve to a public address");
        }
        return publicAddresses;
    };

    private final Call.Factory calls;
    private final ObjectMapper json;
    private final boolean enabled;

    @Autowired
    public HttpGstoneRulebookCatalogLookup(
            ObjectMapper json,
            @Value("${rulepilot.rulebook-discovery.gstone-catalog-enabled:true}") boolean enabled,
            @Value("${rulepilot.rulebook-discovery.source-inspection-timeout:PT8S}") Duration timeout) {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .readTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .callTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .dns(PUBLIC_DNS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                json,
                enabled);
    }

    HttpGstoneRulebookCatalogLookup(Call.Factory calls, boolean enabled) {
        this(calls, JsonMapper.builder().build(), enabled);
    }

    HttpGstoneRulebookCatalogLookup(Call.Factory calls, ObjectMapper json, boolean enabled) {
        if (calls == null) throw new IllegalArgumentException("Gstone catalog HTTP client is required");
        if (json == null) throw new IllegalArgumentException("Gstone catalog JSON mapper is required");
        this.calls = calls;
        this.json = json;
        this.enabled = enabled;
    }

    private static long checkedTimeout(Duration timeout) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Gstone catalog timeout must be between 1 ms and 30 seconds");
        }
        return timeout.toMillis();
    }

    @Override
    public List<OfficialRulebookCandidateFinder.Candidate> find(OfficialRulebookCandidateFinder.Request request) {
        if (!enabled || request == null) return List.of();
        Set<String> exactNames = exactNames(request);
        if (exactNames.isEmpty()) return List.of();
        for (String searchName : searchNames(request)) {
            List<OfficialRulebookCandidateFinder.Candidate> matches = appMatches(searchName, exactNames, request);
            if (!matches.isEmpty()) return matches;
        }
        if (!hasChineseCatalogContext(request)) return List.of();
        for (URI catalogPage : PUBLIC_CATALOG_PAGES) {
            List<OfficialRulebookCandidateFinder.Candidate> matches = htmlMatches(catalogPage, exactNames, request);
            if (!matches.isEmpty()) return matches;
        }
        return List.of();
    }

    private List<OfficialRulebookCandidateFinder.Candidate> appMatches(
            String searchName,
            Set<String> exactNames,
            OfficialRulebookCandidateFinder.Request request) {
        try (Response response = calls.newCall(appSearchRequest(searchName)).execute()) {
            if (!response.isSuccessful() || response.body() == null) return List.of();
            if (response.body().contentLength() > MAX_SEARCH_RESPONSE_BYTES) return List.of();
            byte[] content = response.body().byteStream().readNBytes(MAX_SEARCH_RESPONSE_BYTES + 1);
            if (content.length > MAX_SEARCH_RESPONSE_BYTES) return List.of();
            JsonNode root = json.readTree(content);
            JsonNode games = root.path("data").path("game_list");
            if (root.path("status").asInt(-1) != 200 || !games.isArray()) return List.of();

            var unique = new java.util.LinkedHashMap<String, OfficialRulebookCandidateFinder.Candidate>();
            int inspected = 0;
            for (JsonNode game : games) {
                if (inspected++ >= MAX_SEARCH_RESULTS) break;
                JsonNode idNode = game.path("id");
                if (!idNode.isIntegralNumber() || !idNode.canConvertToInt()) continue;
                int gameId = idNode.intValue();
                if (gameId <= 0 || gameId > MAX_GAME_ID) continue;
                String visibleName = exactVisibleName(game, exactNames, request.language());
                if (visibleName == null) continue;
                URI gamePage = trustedGamePage(
                        "https://www.gstonegames.com/game/info-" + gameId + ".html");
                if (gamePage == null) continue;
                unique.putIfAbsent(gamePage.toASCIIString(), candidate(visibleName, gamePage, request));
            }
            return unique.values().stream().limit(2).toList();
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private List<OfficialRulebookCandidateFinder.Candidate> htmlMatches(
            URI catalogPage,
            Set<String> exactNames,
            OfficialRulebookCandidateFinder.Request request) {
        try (Response response = calls.newCall(htmlRequest(catalogPage)).execute()) {
            if (!response.isSuccessful() || response.body() == null) return List.of();
            String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("text/html") || response.body().contentLength() > MAX_HTML_BYTES) {
                return List.of();
            }
            byte[] html = response.body().byteStream().readNBytes(MAX_HTML_BYTES + 1);
            if (html.length > MAX_HTML_BYTES) return List.of();
            var document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), catalogPage.toASCIIString());
            var unique = new java.util.LinkedHashMap<String, OfficialRulebookCandidateFinder.Candidate>();
            for (Element anchor : document.select("a[href]")) {
                String visibleName = anchor.text().strip().replaceAll("\\s+", " ");
                if (visibleName.isBlank() || !exactNames.contains(normalizedTitle(visibleName))) continue;
                URI gamePage = trustedGamePage(anchor.absUrl("href"));
                if (gamePage == null) continue;
                unique.putIfAbsent(gamePage.toASCIIString(), candidate(visibleName, gamePage, request));
            }
            return unique.values().stream().limit(2).toList();
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private OfficialRulebookCandidateFinder.Candidate candidate(
            String visibleName, URI gamePage, OfficialRulebookCandidateFinder.Request request) {
        return new OfficialRulebookCandidateFinder.Candidate(
                visibleName + " · 集石规则页",
                gamePage.toASCIIString(),
                "集石",
                "",
                request.editionName(),
                OfficialRulebookCandidateFinder.SourcePageHint.GAME_INFORMATION);
    }

    private String exactVisibleName(JsonNode game, Set<String> exactNames, String preferredLanguage) {
        String simplifiedChinese = textual(game.path("sch_name"));
        String english = textual(game.path("eng_name"));
        boolean preferChinese = preferredLanguage != null
                && preferredLanguage.toLowerCase(Locale.ROOT).startsWith("zh");
        List<String> ordered = preferChinese
                ? List.of(simplifiedChinese, english)
                : List.of(english, simplifiedChinese);
        return ordered.stream()
                .filter(name -> !name.isBlank())
                .filter(name -> exactNames.contains(normalizedTitle(name)))
                .findFirst()
                .orElse(null);
    }

    private String textual(JsonNode value) {
        return value.isTextual() ? value.textValue().strip().replaceAll("\\s+", " ") : "";
    }

    private Set<String> exactNames(OfficialRulebookCandidateFinder.Request request) {
        var names = new LinkedHashSet<String>();
        names.add(normalizedTitle(request.gameName()));
        request.officialNames().stream().map(this::normalizedTitle).forEach(names::add);
        names.remove("");
        return Set.copyOf(names);
    }

    private List<String> searchNames(OfficialRulebookCandidateFinder.Request request) {
        var names = new java.util.LinkedHashMap<String, String>();
        java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(request.gameName()), request.officialNames().stream())
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .map(value -> value.replaceAll("\\s+", " "))
                .filter(value -> !value.isBlank())
                .filter(value -> value.codePointCount(0, value.length()) <= 180)
                .forEach(value -> names.putIfAbsent(normalizedTitle(value), value));
        names.remove("");
        return names.values().stream().limit(MAX_SEARCH_NAMES).toList();
    }

    private boolean hasChineseCatalogContext(OfficialRulebookCandidateFinder.Request request) {
        if (request.language() != null && request.language().toLowerCase(Locale.ROOT).startsWith("zh")) return true;
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(request.gameName()), request.officialNames().stream())
                .anyMatch(value -> value != null && value.codePoints().anyMatch(this::isHan));
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private String normalizedTitle(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "")
                .strip();
    }

    private URI trustedGamePage(String value) {
        try {
            URI source = URI.create(value == null ? "" : value.strip()).normalize();
            if (!"https".equalsIgnoreCase(source.getScheme())
                    || source.getHost() == null
                    || !isGstone(source.getHost())
                    || source.getUserInfo() != null
                    || source.getPort() != -1
                    || source.getQuery() != null
                    || source.getFragment() != null
                    || !GAME_PATH.matcher(source.getPath() == null ? "" : source.getPath()).matches()) {
                return null;
            }
            return source;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isGstone(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("gstonegames.com") || normalized.endsWith(".gstonegames.com");
    }

    private Request appSearchRequest(String searchName) throws IOException {
        byte[] body = json.writeValueAsBytes(java.util.Map.of("content", searchName, "page", 1));
        return new Request.Builder()
                .url(PUBLIC_APP_SEARCH.toASCIIString())
                .header("Accept", "application/json,text/plain;q=0.9")
                .header("User-Agent", "RulePilot/0.1 public-rulebook-catalog-review")
                .post(RequestBody.create(body, JSON))
                .build();
    }

    private Request htmlRequest(URI source) {
        return new Request.Builder()
                .url(source.toASCIIString())
                .header("Accept", "text/html,application/xhtml+xml;q=0.9")
                .header("User-Agent", "RulePilot/0.1 public-rulebook-catalog-review")
                .build();
    }
}
