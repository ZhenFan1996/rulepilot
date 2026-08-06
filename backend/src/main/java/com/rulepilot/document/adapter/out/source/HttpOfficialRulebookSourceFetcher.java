package com.rulepilot.document.adapter.out.source;

import com.rulepilot.document.application.MinioStorageProperties;
import com.rulepilot.document.application.OfficialRulebookSourceFetcher;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class HttpOfficialRulebookSourceFetcher implements OfficialRulebookSourceFetcher {

    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> DISALLOWED_BGG_HOSTS = Set.of(
            "boardgamegeek.com", "www.boardgamegeek.com", "geekdo.com", "www.geekdo.com");
    private static final Dns PUBLIC_DNS = hostname -> {
        List<java.net.InetAddress> publicAddresses = Dns.SYSTEM.lookup(hostname).stream()
                .filter(OfficialRulebookNetworkAddressPolicy::isPublic)
                .toList();
        if (publicAddresses.isEmpty()) {
            throw new UnknownHostException("official rulebook source did not resolve to a public address");
        }
        return publicAddresses;
    };

    private final Call.Factory calls;
    private final long maxPdfBytes;

    @Autowired
    public HttpOfficialRulebookSourceFetcher(MinioStorageProperties storage) {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(40, TimeUnit.SECONDS)
                        .dns(PUBLIC_DNS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                storage.maxPdfBytes());
    }

    HttpOfficialRulebookSourceFetcher(Call.Factory calls, long maxPdfBytes) {
        if (calls == null || maxPdfBytes <= 0 || maxPdfBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("official rulebook fetch limits are invalid");
        }
        this.calls = calls;
        this.maxPdfBytes = maxPdfBytes;
    }

    @Override
    public FetchedRulebook fetch(URI source) {
        URI current = trusted(source);
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                try (Response response = calls.newCall(request(current)).execute()) {
                    if (response.isRedirect()) {
                        if (redirects == MAX_REDIRECTS) {
                            throw new IllegalArgumentException("official rulebook source redirected too many times");
                        }
                        current = redirectTarget(current, response.header("Location"));
                        continue;
                    }
                    if (!response.isSuccessful()) {
                        throw new IllegalStateException("official rulebook source returned HTTP " + response.code());
                    }
                    String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                    if (!contentType.startsWith("application/pdf")) {
                        throw new IllegalArgumentException("official rulebook source did not return application/pdf");
                    }
                    if (response.body() == null) {
                        throw new IllegalStateException("official rulebook source returned no body");
                    }
                    long declaredSize = response.body().contentLength();
                    if (declaredSize > maxPdfBytes) {
                        throw new IllegalArgumentException("official rulebook PDF exceeds the configured size limit");
                    }
                    byte[] content = response.body().byteStream().readNBytes(Math.toIntExact(maxPdfBytes + 1));
                    validatePdf(content);
                    return new FetchedRulebook(current, content);
                }
            }
            throw new IllegalArgumentException("official rulebook source redirected too many times");
        } catch (IOException exception) {
            throw new IllegalStateException("official rulebook source is temporarily unavailable", exception);
        }
    }

    URI trusted(URI source) {
        if (source == null
                || !"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || (source.getPort() != -1 && source.getPort() != 443)
                || source.getFragment() != null) {
            throw new IllegalArgumentException("official rulebook source must use standard public HTTPS");
        }
        String host = source.getHost().toLowerCase(Locale.ROOT);
        if (DISALLOWED_BGG_HOSTS.contains(host) || host.endsWith(".boardgamegeek.com") || host.endsWith(".geekdo.com")) {
            throw new IllegalArgumentException("BGG does not expose an authorized rulebook file endpoint");
        }
        return source.normalize();
    }

    URI redirectTarget(URI current, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("official rulebook source redirect is invalid");
        }
        return trusted(current.resolve(location));
    }

    void validatePdf(byte[] content) {
        if (content.length == 0 || content.length > maxPdfBytes) {
            throw new IllegalArgumentException("official rulebook PDF exceeds the configured size limit");
        }
        String signature = new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII);
        if (!"%PDF-".equals(signature)) {
            throw new IllegalArgumentException("official rulebook source content is not a PDF");
        }
    }

    private Request request(URI source) {
        return new Request.Builder()
                .url(source.toASCIIString())
                .header("Accept", "application/pdf")
                .header("User-Agent", "RulePilot/0.1 official-rulebook-import")
                .build();
    }
}
