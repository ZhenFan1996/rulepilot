package com.rulepilot.document.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.application.OfficialRulebookSourceInspector;
import com.rulepilot.document.application.OfficialRulebookSourceInspector.PageSignal;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class HttpOfficialRulebookSourceInspector implements OfficialRulebookSourceInspector {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_LINKS = 80;
    private static final Set<String> BGG_PAGE_DOMAINS = Set.of("boardgamegeek.com", "geekdo.com");
    private static final Set<String> STRUCTURED_GAME_TYPES = Set.of(
            "game",
            "boardgame",
            "product",
            "videogame",
            "http://schema.org/game",
            "http://schema.org/boardgame",
            "http://schema.org/product",
            "http://schema.org/videogame",
            "https://schema.org/game",
            "https://schema.org/boardgame",
            "https://schema.org/product",
            "https://schema.org/videogame");
    private static final Dns PUBLIC_DNS = hostname -> {
        List<java.net.InetAddress> publicAddresses = Dns.SYSTEM.lookup(hostname).stream()
                .filter(OfficialRulebookNetworkAddressPolicy::isPublic)
                .toList();
        if (publicAddresses.isEmpty()) {
            throw new UnknownHostException("rulebook source did not resolve to a public address");
        }
        return publicAddresses;
    };

    private final Call.Factory calls;
    private final ObjectMapper json;
    private final int maxHtmlBytes;
    private final OfficialRulebookImageGalleryParser galleryParser = new OfficialRulebookImageGalleryParser();

    @Autowired
    public HttpOfficialRulebookSourceInspector(
            ObjectMapper json,
            @Value("${rulepilot.rulebook-discovery.source-inspection-timeout:PT8S}") Duration timeout,
            @Value("${rulepilot.rulebook-discovery.max-source-page-bytes:1048576}") int maxHtmlBytes) {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .readTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .callTimeout(checkedTimeout(timeout), TimeUnit.MILLISECONDS)
                        .dns(PUBLIC_DNS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                maxHtmlBytes,
                json);
    }

    public HttpOfficialRulebookSourceInspector(Duration timeout, int maxHtmlBytes) {
        this(new ObjectMapper(), timeout, maxHtmlBytes);
    }

    HttpOfficialRulebookSourceInspector(Call.Factory calls, int maxHtmlBytes) {
        this(calls, maxHtmlBytes, new ObjectMapper());
    }

    HttpOfficialRulebookSourceInspector(Call.Factory calls, int maxHtmlBytes, ObjectMapper json) {
        if (calls == null
                || json == null
                || maxHtmlBytes < 8 * 1024
                || maxHtmlBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("rulebook source inspection limits are invalid");
        }
        this.calls = calls;
        this.json = json;
        this.maxHtmlBytes = maxHtmlBytes;
    }

    private static long checkedTimeout(Duration timeout) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("rulebook source inspection timeout must be between 1 ms and 30 seconds");
        }
        return timeout.toMillis();
    }

    @Override
    public Optional<Inspection> inspect(URI source) {
        URI current = trustedPage(source);
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                try (Response response = calls.newCall(request(current)).execute()) {
                    if (response.isRedirect()) {
                        if (redirects == MAX_REDIRECTS) return Optional.empty();
                        current = redirectTarget(current, response.header("Location"));
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) return Optional.empty();
                    String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                    String disposition = response.header("Content-Disposition", "").toLowerCase(Locale.ROOT);
                    if (contentType.startsWith("application/pdf")
                            || declaresPdfFilename(disposition)) {
                        return Optional.of(new Inspection(current, MediaType.PDF, List.of()));
                    }
                    long declaredSize = response.body().contentLength();
                    if (declaredSize > maxHtmlBytes && isHtml(contentType)) return Optional.empty();
                    byte[] body = response.body().byteStream().readNBytes(maxHtmlBytes + 1);
                    if (hasPdfMagic(body)) {
                        return Optional.of(new Inspection(current, MediaType.PDF, List.of()));
                    }
                    if (body.length > maxHtmlBytes || !isHtml(contentType) && !looksLikeHtml(body)) {
                        return Optional.empty();
                    }
                    Charset charset = response.body().contentType() == null
                            ? StandardCharsets.UTF_8
                            : response.body().contentType().charset(StandardCharsets.UTF_8);
                    Document document = Jsoup.parse(new String(body, charset), current.toASCIIString());
                    if (galleryParser.parse(current, document).isPresent()) {
                        return Optional.of(new Inspection(current, MediaType.IMAGE_GALLERY, List.of()));
                    }
                    List<Link> links = links(document);
                    return Optional.of(new Inspection(current, MediaType.HTML, links, pageSignals(document)));
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private List<Link> links(Document document) {
        Map<String, Link> unique = new LinkedHashMap<>();
        List<Element> anchors = document.select("a[href]");
        anchors.stream().filter(this::isPrioritySourceLink).forEach(anchor -> addLink(unique, anchor));
        anchors.forEach(anchor -> addLink(unique, anchor));
        return List.copyOf(unique.values());
    }

    private Set<PageSignal> pageSignals(Document document) {
        if (document.selectFirst("input[type=password]") != null) {
            return Set.of(PageSignal.LOGIN_REQUIRED);
        }
        Set<PageSignal> signals = new java.util.LinkedHashSet<>();
        if (document.select("a[href]").stream().anyMatch(this::isDownloadableDocumentLink)) {
            signals.add(PageSignal.DOWNLOADABLE_DOCUMENT_LINKS);
        }
        if (hasExplicitEmptyDocumentCollection(document)) {
            signals.add(PageSignal.EXPLICIT_EMPTY_DOCUMENT_COLLECTION);
        }
        if (hasStructuredGameInformation(document)) {
            signals.add(PageSignal.STRUCTURED_GAME_INFORMATION);
        }
        return Set.copyOf(signals);
    }

    private boolean isDownloadableDocumentLink(Element anchor) {
        if (anchor.hasAttr("download")) return true;
        String declaredType = anchor.attr("type").strip().toLowerCase(Locale.ROOT);
        if (declaredType.startsWith("application/pdf")) return true;
        String target = anchor.absUrl("href");
        try {
            URI uri = URI.create(target);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            return path.endsWith(".pdf") || query.contains(".pdf");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean hasExplicitEmptyDocumentCollection(Document document) {
        return !document.select(
                        "[data-document-count=0], [data-rulebook-count=0], [data-file-count=0]")
                .isEmpty();
    }

    private boolean declaresPdfFilename(String disposition) {
        StringTokenizer parameters = new StringTokenizer(disposition, ";");
        while (parameters.hasMoreTokens()) {
            String parameter = parameters.nextToken().strip();
            int separator = parameter.indexOf('=');
            if (separator <= 0) continue;
            String name = parameter.substring(0, separator).strip();
            if (!name.equals("filename") && !name.equals("filename*")) continue;
            String value = parameter.substring(separator + 1).strip();
            boolean startsQuoted = value.startsWith("\"");
            boolean endsQuoted = value.endsWith("\"");
            if (startsQuoted != endsQuoted) continue;
            if (value.length() >= 2 && startsQuoted) {
                value = value.substring(1, value.length() - 1);
            }
            if (name.equals("filename*")) {
                int encodingSeparator = value.indexOf("''");
                if (encodingSeparator >= 0) value = value.substring(encodingSeparator + 2);
                try {
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
            }
            if (value.endsWith(".pdf")) return true;
        }
        return false;
    }

    private boolean hasStructuredGameInformation(Document document) {
        if (document.select("meta[property=og:type]").stream()
                        .map(element -> element.attr("content").strip().toLowerCase(Locale.ROOT))
                        .anyMatch(Set.of("product", "game")::contains)
                || document.select("[itemtype]").stream()
                        .map(element -> element.attr("itemtype"))
                        .anyMatch(this::hasExactStructuredGameType)) {
            return true;
        }
        return document.select("script[type=application/ld+json]").stream()
                .anyMatch(this::hasParsedStructuredGameType);
    }

    private boolean hasExactStructuredGameType(String itemTypes) {
        StringTokenizer tokens = new StringTokenizer(itemTypes);
        while (tokens.hasMoreTokens()) {
            if (isExactStructuredGameType(tokens.nextToken())) return true;
        }
        return false;
    }

    private boolean hasParsedStructuredGameType(Element script) {
        try {
            JsonNode root = json.readTree(script.data());
            return root != null
                    && root.findValues("@type").stream().anyMatch(this::hasExactStructuredGameType);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean hasExactStructuredGameType(JsonNode type) {
        if (type.isTextual()) return isExactStructuredGameType(type.textValue());
        if (!type.isArray()) return false;
        for (JsonNode value : type) {
            if (value.isTextual() && isExactStructuredGameType(value.textValue())) return true;
        }
        return false;
    }

    private boolean isExactStructuredGameType(String type) {
        return type != null && STRUCTURED_GAME_TYPES.contains(type.strip().toLowerCase(Locale.ROOT));
    }

    private void addLink(Map<String, Link> unique, Element anchor) {
        if (unique.size() == MAX_LINKS) return;
        URI target = publicHttps(anchor.absUrl("href"));
        if (target == null) return;
        String label = linkLabel(anchor);
        unique.putIfAbsent(target.toASCIIString(), new Link(target, label));
    }

    private boolean isPrioritySourceLink(Element anchor) {
        String href = anchor.attr("href").toLowerCase(Locale.ROOT);
        String label = linkLabel(anchor).toLowerCase(Locale.ROOT);
        return href.contains("/game/doc-")
                || href.contains(".pdf")
                || href.contains("download")
                || label.contains("rule")
                || label.contains("manual")
                || label.contains("instruction")
                || label.contains("规则")
                || label.contains("規則")
                || label.contains("下载")
                || label.contains("下載");
    }

    private String linkLabel(Element anchor) {
        String label = anchor.text().strip();
        if (label.isBlank()) label = anchor.attr("aria-label").strip();
        if (label.isBlank()) label = anchor.attr("title").strip();
        if (label.isBlank() && anchor.parent() != null) label = anchor.parent().text().strip();
        label = label.replaceAll("\\s+", " ");
        return label.length() <= 240 ? label : label.substring(0, 240);
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

    private URI trustedPage(URI source) {
        URI checked = publicHttps(source == null ? "" : source.toASCIIString());
        if (checked == null) throw new IllegalArgumentException("rulebook source page must use standard public HTTPS");
        String host = checked.getHost().toLowerCase(Locale.ROOT);
        if (BGG_PAGE_DOMAINS.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) {
            throw new IllegalArgumentException("BGG pages require the supported browser handoff");
        }
        return checked;
    }

    private URI redirectTarget(URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("rulebook source page redirect is invalid");
        }
        return trustedPage(current.resolve(location));
    }

    private boolean isHtml(String contentType) {
        return contentType.isBlank()
                || contentType.startsWith("text/html")
                || contentType.startsWith("application/xhtml+xml");
    }

    private boolean hasPdfMagic(byte[] body) {
        return body.length >= 5
                && body[0] == '%'
                && body[1] == 'P'
                && body[2] == 'D'
                && body[3] == 'F'
                && body[4] == '-';
    }

    private boolean looksLikeHtml(byte[] body) {
        int length = Math.min(body.length, 256);
        String prefix = new String(body, 0, length, StandardCharsets.US_ASCII).stripLeading().toLowerCase(Locale.ROOT);
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<head");
    }

    private Request request(URI source) {
        return new Request.Builder()
                .url(source.toASCIIString())
                .header("Accept", "application/pdf,text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                .header("User-Agent", "RulePilot/0.1 rulebook-source-review")
                .build();
    }
}
