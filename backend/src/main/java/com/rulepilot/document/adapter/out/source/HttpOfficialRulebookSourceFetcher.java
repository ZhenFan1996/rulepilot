package com.rulepilot.document.adapter.out.source;

import com.rulepilot.document.application.MinioStorageProperties;
import com.rulepilot.document.application.OfficialRulebookPdfCompressor;
import com.rulepilot.document.application.OfficialRulebookSourceAccessException;
import com.rulepilot.document.application.OfficialRulebookSourceFetcher;
import com.rulepilot.document.application.PhotographedRulebookAssembler;
import com.rulepilot.document.application.PhotographedRulebookUploadService.PhotoPage;
import com.rulepilot.document.adapter.out.pdf.PdfBoxOfficialRulebookPdfCompressor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class HttpOfficialRulebookSourceFetcher implements OfficialRulebookSourceFetcher {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final long ABSOLUTE_MAX_COMPRESSIBLE_PDF_BYTES = 256L * 1024 * 1024;
    private static final long DEFAULT_MAX_COMPRESSIBLE_PDF_BYTES = 100L * 1024 * 1024;
    private static final String GSTONE_READABLE_IMAGE_TRANSFORM =
            "x-oss-process=image/auto-orient,1/resize,m_lfit,w_2000/format,jpg/quality,q_88";
    private static final Set<String> PAGE_ONLY_BGG_HOSTS = Set.of(
            "boardgamegeek.com", "www.boardgamegeek.com", "geekdo.com", "www.geekdo.com");
    private static final Set<String> BGG_DOWNLOAD_HOSTS = Set.of("boardgamegeek.com", "www.boardgamegeek.com");
    private static final Pattern BGG_DOWNLOAD_PATH = Pattern.compile(
            "^/file/download_redirect/[a-fA-F0-9]{48}/[^/]{1,512}$");
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
    private final long maxCompressiblePdfBytes;
    private final int maxHtmlBytes;
    private final PhotographedRulebookAssembler galleryAssembler;
    private final OfficialRulebookPdfCompressor pdfCompressor;
    private final OfficialRulebookImageGalleryParser galleryParser = new OfficialRulebookImageGalleryParser();

    @Autowired
    public HttpOfficialRulebookSourceFetcher(
            MinioStorageProperties storage,
            PhotographedRulebookAssembler galleryAssembler,
            OfficialRulebookPdfCompressor pdfCompressor,
            @Value("${rulepilot.rulebook-import.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${rulepilot.rulebook-import.read-timeout:PT1M30S}") Duration readTimeout,
            @Value("${rulepilot.rulebook-import.call-timeout:PT10M}") Duration callTimeout,
            @Value("${rulepilot.rulebook-import.max-compressible-pdf-bytes:104857600}")
                    long maxCompressiblePdfBytes,
            @Value("${rulepilot.rulebook-discovery.max-source-page-bytes:1048576}") int maxHtmlBytes) {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(checkedTimeout(connectTimeout, "connect"), TimeUnit.MILLISECONDS)
                        .readTimeout(checkedTimeout(readTimeout, "read"), TimeUnit.MILLISECONDS)
                        .callTimeout(checkedTimeout(callTimeout, "call"), TimeUnit.MILLISECONDS)
                        .dns(PUBLIC_DNS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                storage.maxPdfBytes(),
                Math.max(storage.maxPdfBytes(), maxCompressiblePdfBytes),
                maxHtmlBytes,
                galleryAssembler,
                pdfCompressor);
    }

    public HttpOfficialRulebookSourceFetcher(
            MinioStorageProperties storage,
            PhotographedRulebookAssembler galleryAssembler,
            Duration connectTimeout,
            Duration readTimeout,
            Duration callTimeout,
            int maxHtmlBytes) {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(checkedTimeout(connectTimeout, "connect"), TimeUnit.MILLISECONDS)
                        .readTimeout(checkedTimeout(readTimeout, "read"), TimeUnit.MILLISECONDS)
                        .callTimeout(checkedTimeout(callTimeout, "call"), TimeUnit.MILLISECONDS)
                        .dns(PUBLIC_DNS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build(),
                storage.maxPdfBytes(),
                Math.max(storage.maxPdfBytes(), DEFAULT_MAX_COMPRESSIBLE_PDF_BYTES),
                maxHtmlBytes,
                galleryAssembler,
                new PdfBoxOfficialRulebookPdfCompressor());
    }

    private static long checkedTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("official rulebook " + name + " timeout must be between 1 ms and 15 minutes");
        }
        return timeout.toMillis();
    }

    HttpOfficialRulebookSourceFetcher(Call.Factory calls, long maxPdfBytes) {
        this(
                calls,
                maxPdfBytes,
                maxPdfBytes,
                1024 * 1024,
                pages -> {
                    throw new IllegalArgumentException("image-gallery assembly is unavailable in this test fixture");
                },
                (source, maximum) -> {
                    throw new IllegalArgumentException("PDF compression is unavailable in this test fixture");
                });
    }

    HttpOfficialRulebookSourceFetcher(
            Call.Factory calls,
            long maxPdfBytes,
            int maxHtmlBytes,
            PhotographedRulebookAssembler galleryAssembler) {
        this(
                calls,
                maxPdfBytes,
                maxPdfBytes,
                maxHtmlBytes,
                galleryAssembler,
                (source, maximum) -> {
                    throw new IllegalArgumentException("PDF compression is unavailable in this test fixture");
                });
    }

    HttpOfficialRulebookSourceFetcher(
            Call.Factory calls,
            long maxPdfBytes,
            long maxCompressiblePdfBytes,
            int maxHtmlBytes,
            PhotographedRulebookAssembler galleryAssembler,
            OfficialRulebookPdfCompressor pdfCompressor) {
        if (calls == null
                || maxPdfBytes <= 0
                || maxPdfBytes >= Integer.MAX_VALUE
                || maxCompressiblePdfBytes < maxPdfBytes
                || maxCompressiblePdfBytes > ABSOLUTE_MAX_COMPRESSIBLE_PDF_BYTES
                || maxHtmlBytes < 8 * 1024
                || maxHtmlBytes > 4 * 1024 * 1024
                || galleryAssembler == null
                || pdfCompressor == null) {
            throw new IllegalArgumentException("official rulebook fetch limits are invalid");
        }
        this.calls = calls;
        this.maxPdfBytes = maxPdfBytes;
        this.maxCompressiblePdfBytes = maxCompressiblePdfBytes;
        this.maxHtmlBytes = maxHtmlBytes;
        this.galleryAssembler = galleryAssembler;
        this.pdfCompressor = pdfCompressor;
    }

    @Override
    public FetchedRulebook fetch(URI source) {
        return fetch(source, ProgressListener.none());
    }

    @Override
    public FetchedRulebook fetch(URI source, ProgressListener progress) {
        URI current = trusted(source);
        boolean interactiveSource = isBggDownloadRedirect(current);
        try {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                try (Response response = calls.newCall(request(current)).execute()) {
                    if (response.isRedirect()) {
                        if (redirects == MAX_REDIRECTS) {
                            throw new IllegalArgumentException("official rulebook source redirected too many times");
                        }
                        try {
                            current = redirectTarget(current, response.header("Location"));
                        } catch (IllegalArgumentException exception) {
                            if (interactiveSource) throw interactiveBrowserRequired();
                            throw exception;
                        }
                        continue;
                    }
                    if (response.code() == 401
                            || response.code() == 403
                            || interactiveSource && (response.code() == 404 || response.code() == 410)) {
                        throw new OfficialRulebookSourceAccessException(
                                OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED,
                                "rulebook source requires an interactive browser session");
                    }
                    if (!response.isSuccessful()) {
                        throw new IllegalStateException("official rulebook source returned HTTP " + response.code());
                    }
                    if (response.body() == null) {
                        throw new IllegalStateException("official rulebook source returned no body");
                    }
                    String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                    String disposition = response.header("Content-Disposition", "").toLowerCase(Locale.ROOT);
                    if (isHtml(contentType)) {
                        return fetchImageGallery(current, response, progress);
                    }
                    if (!contentType.startsWith("application/pdf")
                            && !(disposition.contains("filename=") && disposition.contains(".pdf"))
                            && !contentType.startsWith("application/octet-stream")) {
                        throw new IllegalArgumentException("official rulebook source did not return a PDF or rulebook gallery");
                    }
                    long declaredSize = response.body().contentLength();
                    if (declaredSize > maxCompressiblePdfBytes) {
                        throw new IllegalArgumentException(
                                "official rulebook PDF exceeds the safe compression input limit");
                    }
                    Long totalBytes = declaredSize > 0 ? declaredSize : null;
                    progress.downloadStarted(totalBytes);
                    byte[] content = readBounded(response, progress, totalBytes, maxCompressiblePdfBytes);
                    validatePdfMagic(content);
                    if (content.length > maxPdfBytes) {
                        progress.compressing();
                        content = pdfCompressor.compress(content, maxPdfBytes);
                    }
                    progress.verifying();
                    validatePdf(content);
                    return new FetchedRulebook(current, content);
                }
            }
            throw new IllegalArgumentException("official rulebook source redirected too many times");
        } catch (IOException exception) {
            throw new IllegalStateException("official rulebook source is temporarily unavailable", exception);
        }
    }

    private FetchedRulebook fetchImageGallery(URI source, Response response, ProgressListener progress)
            throws IOException {
        long declaredSize = response.body().contentLength();
        if (declaredSize > maxHtmlBytes) throw interactiveBrowserRequired();
        byte[] html = response.body().byteStream().readNBytes(maxHtmlBytes + 1);
        if (html.length > maxHtmlBytes) throw interactiveBrowserRequired();
        Charset charset = response.body().contentType() == null
                ? StandardCharsets.UTF_8
                : response.body().contentType().charset(StandardCharsets.UTF_8);
        var document = Jsoup.parse(new String(html, charset), source.toASCIIString());
        var gallery = galleryParser.parse(source, document).orElseThrow(this::interactiveBrowserRequired);

        progress.downloadStarted(null);
        long downloaded = 0;
        var pages = new ArrayList<PhotoPage>(gallery.pages().size());
        for (int index = 0; index < gallery.pages().size(); index++) {
            FetchedImage page = fetchImage(source, gallery.pages().get(index), downloaded, progress);
            downloaded += page.content().length;
            if (downloaded > maxPdfBytes) {
                throw new IllegalArgumentException("rulebook page images exceed the configured size limit");
            }
            pages.add(new PhotoPage(
                    "page-%02d.%s".formatted(index + 1, page.extension()), page.contentType(), page.content()));
            progress.downloaded(downloaded, null);
        }
        byte[] pdf = galleryAssembler.assemble(pages).pdf();
        if (pdf.length > maxPdfBytes) {
            throw new IllegalArgumentException("assembled rulebook PDF exceeds the configured size limit");
        }
        progress.verifying();
        validatePdf(pdf);
        return new FetchedRulebook(source, pdf);
    }

    private FetchedImage fetchImage(
            URI gallerySource, URI imageSource, long previouslyDownloaded, ProgressListener progress)
            throws IOException {
        URI current = readableGalleryImage(trustedGalleryImage(gallerySource, imageSource));
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            try (Response response = calls.newCall(imageRequest(current, gallerySource)).execute()) {
                if (response.isRedirect()) {
                    if (redirects == MAX_REDIRECTS) {
                        throw new IllegalArgumentException("rulebook page image redirected too many times");
                    }
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalArgumentException("rulebook page image redirect is invalid");
                    }
                    current = readableGalleryImage(trustedGalleryImage(gallerySource, current.resolve(location)));
                    continue;
                }
                if (response.code() == 401 || response.code() == 403) throw interactiveBrowserRequired();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("rulebook page image returned HTTP " + response.code());
                }
                String contentType = normalizedImageType(response.header("Content-Type", ""));
                long declaredSize = response.body().contentLength();
                long remaining = maxPdfBytes - previouslyDownloaded;
                if (declaredSize > MAX_IMAGE_BYTES || declaredSize > remaining) {
                    throw new IllegalArgumentException("rulebook page image exceeds the configured size limit");
                }
                byte[] content = readImageBounded(response, remaining, previouslyDownloaded, progress);
                validateImage(content, contentType);
                String extension = "image/png".equals(contentType) ? "png" : "jpg";
                return new FetchedImage(contentType, extension, content);
            }
        }
        throw new IllegalArgumentException("rulebook page image redirected too many times");
    }

    private URI readableGalleryImage(URI source) {
        String host = source.getHost().toLowerCase(Locale.ROOT);
        String path = source.getPath() == null ? "" : source.getPath();
        if (!host.equals("oss.gstonegames.com")
                || !path.startsWith("/static/image/document/")
                || source.getRawQuery() != null) {
            return source;
        }
        return URI.create(source.toASCIIString() + "?" + GSTONE_READABLE_IMAGE_TRANSFORM);
    }

    private byte[] readImageBounded(
            Response response, long remaining, long previouslyDownloaded, ProgressListener progress)
            throws IOException {
        var content = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long downloaded = 0;
        long lastReported = 0;
        try (var input = response.body().byteStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                downloaded += read;
                if (downloaded > MAX_IMAGE_BYTES || downloaded > remaining) {
                    throw new IllegalArgumentException("rulebook page image exceeds the configured size limit");
                }
                content.write(buffer, 0, read);
                if (downloaded - lastReported >= 256 * 1024) {
                    progress.downloaded(previouslyDownloaded + downloaded, null);
                    lastReported = downloaded;
                }
            }
        }
        return content.toByteArray();
    }

    private String normalizedImageType(String contentType) {
        String normalized = contentType == null
                ? ""
                : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        if ("image/jpg".equals(normalized)) return "image/jpeg";
        if (!Set.of("image/jpeg", "image/png").contains(normalized)) {
            throw new IllegalArgumentException("rulebook page did not return a JPEG or PNG image");
        }
        return normalized;
    }

    private void validateImage(byte[] content, String contentType) {
        boolean jpeg = content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff;
        boolean png = content.length >= 8
                && (content[0] & 0xff) == 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G';
        if ("image/jpeg".equals(contentType) && !jpeg || "image/png".equals(contentType) && !png) {
            throw new IllegalArgumentException("rulebook page content is not the declared image type");
        }
    }

    private URI trustedGalleryImage(URI gallerySource, URI imageSource) {
        URI checked = trusted(imageSource);
        String galleryHost = gallerySource.getHost().toLowerCase(Locale.ROOT);
        String imageHost = checked.getHost().toLowerCase(Locale.ROOT);
        boolean sameHost = galleryHost.equals(imageHost);
        boolean gstoneCdn = isDomain(galleryHost, "gstonegames.com") && isDomain(imageHost, "gstonegames.com");
        if (!sameHost && !gstoneCdn) {
            throw new IllegalArgumentException("rulebook page image must remain on the reviewed source domain");
        }
        return checked;
    }

    private boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private boolean isHtml(String contentType) {
        return contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml");
    }

    private byte[] readBounded(
            Response response, ProgressListener progress, Long totalBytes, long maximumBytes) throws IOException {
        var content = new ByteArrayOutputStream(totalBytes == null
                ? 64 * 1024
                : Math.toIntExact(Math.min(totalBytes, Math.min(maximumBytes, 1024 * 1024))));
        byte[] buffer = new byte[16 * 1024];
        long downloaded = 0;
        long lastReported = 0;
        try (var input = response.body().byteStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                downloaded += read;
                if (downloaded > maximumBytes) {
                    throw new IllegalArgumentException("official rulebook PDF exceeds the safe compression input limit");
                }
                content.write(buffer, 0, read);
                if (downloaded - lastReported >= 256 * 1024) {
                    progress.downloaded(downloaded, totalBytes);
                    lastReported = downloaded;
                }
            }
        }
        progress.downloaded(downloaded, totalBytes);
        return content.toByteArray();
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
        boolean bggPageHost = PAGE_ONLY_BGG_HOSTS.contains(host)
                || host.endsWith(".boardgamegeek.com")
                || host.endsWith(".geekdo.com");
        if (bggPageHost && !isBggDownloadRedirect(source)) {
            throw new IllegalArgumentException("BGG does not expose an authorized rulebook file endpoint");
        }
        return source.normalize();
    }

    private boolean isBggDownloadRedirect(URI source) {
        String host = source.getHost() == null ? "" : source.getHost().toLowerCase(Locale.ROOT);
        return BGG_DOWNLOAD_HOSTS.contains(host)
                && source.getQuery() == null
                && BGG_DOWNLOAD_PATH.matcher(source.getPath() == null ? "" : source.getPath()).matches();
    }

    private OfficialRulebookSourceAccessException interactiveBrowserRequired() {
        return new OfficialRulebookSourceAccessException(
                OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED,
                "rulebook download requires an interactive browser session");
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
        validatePdfMagic(content);
    }

    private void validatePdfMagic(byte[] content) {
        String signature = new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII);
        if (!"%PDF-".equals(signature)) {
            throw new IllegalArgumentException("official rulebook source content is not a PDF");
        }
    }

    private Request request(URI source) {
        return new Request.Builder()
                .url(source.toASCIIString())
                .header("Accept", "application/pdf,text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                .header("User-Agent", "RulePilot/0.1 user-confirmed-rulebook-import")
                .build();
    }

    private Request imageRequest(URI source, URI gallerySource) {
        return new Request.Builder()
                .url(source.toASCIIString())
                .header("Accept", "image/jpeg,image/png;q=0.9")
                .header("Referer", gallerySource.toASCIIString())
                .header("User-Agent", "RulePilot/0.1 user-confirmed-rulebook-import")
                .build();
    }

    private record FetchedImage(String contentType, String extension, byte[] content) {}
}
