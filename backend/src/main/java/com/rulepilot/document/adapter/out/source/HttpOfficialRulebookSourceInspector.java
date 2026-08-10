package com.rulepilot.document.adapter.out.source;

import com.rulepilot.document.application.OfficialRulebookSourceInspector;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final int maxHtmlBytes;
    private final OfficialRulebookImageGalleryParser galleryParser = new OfficialRulebookImageGalleryParser();

    @Autowired
    public HttpOfficialRulebookSourceInspector(
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
                maxHtmlBytes);
    }

    HttpOfficialRulebookSourceInspector(Call.Factory calls, int maxHtmlBytes) {
        if (calls == null || maxHtmlBytes < 8 * 1024 || maxHtmlBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("rulebook source inspection limits are invalid");
        }
        this.calls = calls;
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
                    if (contentType.startsWith("application/pdf") || disposition.contains("filename=") && disposition.contains(".pdf")) {
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
                    return Optional.of(new Inspection(current, MediaType.HTML, links(document)));
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
