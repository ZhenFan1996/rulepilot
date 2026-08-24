package com.rulepilot.catalog.adapter.out.cover;

import com.rulepilot.catalog.adapter.out.cover.CoverImageFetcher.SourceAbsentException;
import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fetches only bounded public HTTPS images and converts them before persistence. */
@Component
@Profile("!test")
public class HttpCoverImageFetcher implements CoverImageFetcher {

    private static final int MAX_ORIGIN_BYTES = 16_000_000;
    private static final int MAX_REDIRECTS = 2;
    private static final Dns PUBLIC_DNS = hostname -> {
        List<InetAddress> publicAddresses = Dns.SYSTEM.lookup(hostname).stream()
                .filter(HttpCoverImageFetcher::isPublicAddress)
                .toList();
        if (publicAddresses.isEmpty()) throw new UnknownHostException("cover source did not resolve to a public address");
        return publicAddresses;
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .dns(PUBLIC_DNS)
            .followRedirects(false)
            .build();
    private final CoverThumbnailer thumbnailer = new CoverThumbnailer();

    @Override
    public Thumbnail fetch(URI source, DurableCoverThumbnailService.Profile profile) {
        try (Response response = request(source)) {
            if (response.code() == 404 || response.code() == 410) {
                throw new SourceAbsentException("cover origin no longer has this image");
            }
            if (!response.isSuccessful()) throw new IllegalStateException("cover origin returned HTTP " + response.code());
            String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) throw new IllegalArgumentException("cover origin did not return an image");
            if (response.body() == null) throw new IllegalStateException("cover origin returned no image body");
            byte[] content = response.body().byteStream().readNBytes(MAX_ORIGIN_BYTES + 1);
            if (content.length > MAX_ORIGIN_BYTES) throw new IllegalArgumentException("cover origin image is too large");
            return thumbnailer.create(content, profile);
        } catch (IOException exception) {
            throw new IllegalStateException("cover origin is temporarily unavailable", exception);
        }
    }

    private Response request(URI source) throws IOException {
        URI current = trusted(source);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            Response response = http.newCall(new Request.Builder()
                    .url(current.toASCIIString())
                    .header("Accept", "image/png,image/jpeg,image/gif;q=0.9,*/*;q=0.1")
                    .header("User-Agent", "RulePilot/0.1 durable-cover-cache")
                    .build()).execute();
            if (!response.isRedirect()) return response;
            String location = response.header("Location");
            response.close();
            if (location == null || location.isBlank()) throw new IllegalArgumentException("cover origin redirect is invalid");
            current = trusted(current.resolve(location));
        }
        throw new IllegalArgumentException("cover origin redirected too many times");
    }

    private URI trusted(URI source) {
        if (!"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || (source.getPort() != -1 && source.getPort() != 443)) {
            throw new IllegalArgumentException("cover source must stay on standard public HTTPS");
        }
        return source.normalize();
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (address instanceof Inet6Address ipv6) return (ipv6.getAddress()[0] & 0xfe) != 0xfc;
        if (!(address instanceof Inet4Address)) return false;
        byte[] value = address.getAddress();
        int first = Byte.toUnsignedInt(value[0]);
        int second = Byte.toUnsignedInt(value[1]);
        int third = Byte.toUnsignedInt(value[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && (second == 0 || second == 2 || second == 168)) return false;
        if (first == 198 && (second == 18 || second == 19 || second == 51)) return false;
        return first != 203 || second != 0 || third != 113;
    }
}
