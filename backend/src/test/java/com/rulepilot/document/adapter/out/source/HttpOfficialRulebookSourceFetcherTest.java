package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.application.OfficialRulebookSourceAccessException;
import com.rulepilot.document.application.OfficialRulebookSourceFetcher;
import com.rulepilot.document.adapter.out.pdf.PdfBoxPhotographedRulebookAssembler;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

class HttpOfficialRulebookSourceFetcherTest {

    private static final MediaType PDF = MediaType.parse("application/pdf");

    @Test
    void followsOnlyValidatedRedirectsAndReturnsABoundedPdf() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    int call = calls.getAndIncrement();
                    assertThat(chain.request().header("Accept")).startsWith("application/pdf");
                    assertThat(chain.request().header("Authorization")).isNull();
                    assertThat(chain.request().header("Cookie")).isNull();
                    if (call == 0) {
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(302)
                                .message("Found")
                                .header("Location", "https://cdn.publisher.example/rules.pdf")
                                .body(ResponseBody.create(new byte[0], null))
                                .build();
                    }
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/pdf; charset=binary")
                            .body(ResponseBody.create("%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII), PDF))
                            .build();
                })
                .followRedirects(false)
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 1_024);

        var fetched = fetcher.fetch(URI.create("https://publisher.example/download"));

        assertThat(calls).hasValue(2);
        assertThat(fetched.finalSource()).isEqualTo(URI.create("https://cdn.publisher.example/rules.pdf"));
        assertThat(new String(fetched.content(), StandardCharsets.US_ASCII)).startsWith("%PDF-");
    }

    @Test
    void reportsDeclaredBytesAndVerificationFromTheActualResponseStream() {
        byte[] pdf = ("%PDF-1.7\n" + "x".repeat(300_000)).getBytes(StandardCharsets.US_ASCII);
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(ResponseBody.create(pdf, PDF))
                        .build())
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 400_000);
        List<String> events = new ArrayList<>();

        fetcher.fetch(URI.create("https://publisher.example/rules.pdf"), new OfficialRulebookSourceFetcher.ProgressListener() {
            @Override public void downloadStarted(Long totalBytes) { events.add("start:" + totalBytes); }
            @Override public void downloaded(long downloadedBytes, Long totalBytes) {
                events.add("bytes:" + downloadedBytes + "/" + totalBytes);
            }
            @Override public void downloadCompleted() { events.add("download-complete"); }
            @Override public void verifying() { events.add("verify"); }
        });

        assertThat(events.getFirst()).isEqualTo("start:" + pdf.length);
        assertThat(events).anyMatch(event -> event.startsWith("bytes:262144/"));
        assertThat(events).containsSubsequence(
                "bytes:" + pdf.length + "/" + pdf.length,
                "download-complete",
                "verify");
        assertThat(events.getLast()).isEqualTo("verify");
    }

    @Test
    void rejectsNonPublicTransportAndBggPageScrapingTargets() {
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(
                new OkHttpClient(), 1_024);

        assertThatThrownBy(() -> fetcher.trusted(URI.create("http://publisher.example/rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> fetcher.trusted(URI.create("https://user:pass@publisher.example/rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fetcher.trusted(URI.create("https://publisher.example:8443/rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fetcher.trusted(URI.create("https://boardgamegeek.com/filepage/1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BGG");
        assertThatThrownBy(() -> fetcher.trusted(URI.create(
                        "https://boardgamegeek.com/file/download_redirect/not-an-id/rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BGG");
        assertThatThrownBy(() -> fetcher.trusted(URI.create(
                        "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/rules.pdf?guessed=true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BGG");
        assertThatThrownBy(() -> fetcher.redirectTarget(
                        URI.create("https://publisher.example/rules.pdf"), "http://127.0.0.1/internal"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void followsAnExactBggDownloadRedirectWithoutForwardingCredentials() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    int call = calls.getAndIncrement();
                    assertThat(chain.request().header("Authorization")).isNull();
                    assertThat(chain.request().header("Cookie")).isNull();
                    if (call == 0) {
                        assertThat(chain.request().url().encodedPath())
                                .isEqualTo("/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf");
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(302)
                                .message("Found")
                                .header("Location", "https://cf.geekdo-static.com/file/rules.pdf")
                                .body(ResponseBody.create(new byte[0], null))
                                .build();
                    }
                    assertThat(chain.request().url().host()).isEqualTo("cf.geekdo-static.com");
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/pdf")
                            .body(ResponseBody.create("%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII), PDF))
                            .build();
                })
                .followRedirects(false)
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 1_024);

        var result = fetcher.fetch(URI.create(
                "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf"));

        assertThat(calls).hasValue(2);
        assertThat(result.finalSource()).isEqualTo(URI.create("https://cf.geekdo-static.com/file/rules.pdf"));
        assertThat(new String(result.content(), StandardCharsets.US_ASCII)).startsWith("%PDF-");
    }

    @Test
    void reportsThatAnObservedBggDownloadNeedsAnInteractiveBrowserInsteadOfCallingItInvalid() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=UTF-8")
                        .body(ResponseBody.create("sign in to download", MediaType.parse("text/html")))
                        .build())
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 1_024);

        assertThatThrownBy(() -> fetcher.fetch(URI.create(
                        "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/catalog-game-rules.pdf")))
                .isInstanceOf(OfficialRulebookSourceAccessException.class)
                .satisfies(exception -> assertThat(((OfficialRulebookSourceAccessException) exception).reason())
                        .isEqualTo(OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED));
    }

    @Test
    void downloadsAnExplicitGstoneRulebookGalleryAndAssemblesItsOrderedPagesIntoAPdf() throws IOException {
        byte[] firstPage = jpeg(0x336699);
        byte[] secondPage = jpeg(0x996633);
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    assertThat(chain.request().header("Authorization")).isNull();
                    assertThat(chain.request().header("Cookie")).isNull();
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        String html = """
                                <!doctype html><html><head><title>官方规则书</title></head><body>
                                <div id="preview_imgs">
                                  <p><img data-original="https://oss.gstonegames.com/static/image/document/page-01.jpg"></p>
                                  <p><img data-original="https://oss.gstonegames.com/static/image/document/page-02.jpg"></p>
                                </div>
                                <img src="https://oss.gstonegames.com/static/image/banner/not-a-page.jpg">
                                </body></html>
                                """;
                        return response(chain.request(), "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                    }
                    assertThat(chain.request().header("Referer"))
                            .isEqualTo("https://www.gstonegames.com/game/doc-1234.html");
                    assertThat(chain.request().url().encodedQuery())
                            .isEqualTo("x-oss-process=image/auto-orient,1/resize,m_lfit,w_2000/format,jpg/quality,q_88");
                    return response(chain.request(), "image/jpeg", call == 1 ? firstPage : secondPage);
                })
                .build();
        List<String> events = new ArrayList<>();
        var pdfAssembler = new PdfBoxPhotographedRulebookAssembler();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                5 * 1024 * 1024,
                64 * 1024,
                pages -> {
                    events.add("assemble");
                    return pdfAssembler.assemble(pages);
                });

        var fetched = fetcher.fetch(
                URI.create("https://www.gstonegames.com/game/doc-1234.html"),
                new OfficialRulebookSourceFetcher.ProgressListener() {
                    @Override public void downloadStarted(Long totalBytes) { events.add("start:" + totalBytes); }
                    @Override public void downloaded(long downloadedBytes, Long totalBytes) {
                        events.add("bytes:" + downloadedBytes + "/" + totalBytes);
                    }
                    @Override public void downloadCompleted() { events.add("download-complete"); }
                    @Override public void verifying() { events.add("verify"); }
                });

        assertThat(calls).hasValue(3);
        assertThat(fetched.finalSource()).isEqualTo(URI.create("https://www.gstonegames.com/game/doc-1234.html"));
        assertThat(new String(fetched.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var pdf = Loader.loadPDF(fetched.content())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(events.getFirst()).isEqualTo("start:null");
        assertThat(events).containsSubsequence(
                "bytes:" + (firstPage.length + secondPage.length) + "/null",
                "download-complete",
                "assemble",
                "verify");
        assertThat(events.getLast()).isEqualTo("verify");
    }

    @Test
    void downloadsIndependentGalleryPagesWithBoundedConcurrencyAndKeepsTheirSourceOrder() {
        int pageCount = 8;
        AtomicInteger activeDownloads = new AtomicInteger();
        AtomicInteger maximumConcurrentDownloads = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().equals("/game/doc-1234.html")) {
                        StringBuilder html = new StringBuilder("<div id=\"preview_imgs\">");
                        for (int page = 1; page <= pageCount; page++) {
                            html.append("<img src=\"https://oss.gstonegames.com/static/image/document/page-%02d.jpg\">"
                                    .formatted(page));
                        }
                        return response(chain.request(), "text/html; charset=utf-8",
                                html.append("</div>").toString().getBytes(StandardCharsets.UTF_8));
                    }
                    int active = activeDownloads.incrementAndGet();
                    maximumConcurrentDownloads.accumulateAndGet(active, Math::max);
                    try {
                        Thread.sleep(75);
                        return response(chain.request(), "image/jpeg",
                                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("fixture download interrupted", interrupted);
                    } finally {
                        activeDownloads.decrementAndGet();
                    }
                })
                .build();
        List<String> assembledPageNames = new ArrayList<>();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                5 * 1024 * 1024,
                64 * 1024,
                pages -> {
                    pages.forEach(page -> assembledPageNames.add(page.originalFilename()));
                    return new com.rulepilot.document.application.PhotographedRulebookAssembler.AssembledRulebook(
                            "rulebook.pdf", "%PDF-gallery".getBytes(StandardCharsets.US_ASCII));
                });

        var fetched = fetcher.fetch(URI.create("https://www.gstonegames.com/game/doc-1234.html"));

        assertThat(maximumConcurrentDownloads).hasValueBetween(2, 4);
        assertThat(assembledPageNames).containsExactly(
                "page-01.jpg", "page-02.jpg", "page-03.jpg", "page-04.jpg",
                "page-05.jpg", "page-06.jpg", "page-07.jpg", "page-08.jpg");
        assertThat(new String(fetched.content(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-gallery");
    }

    @Test
    void keepsTheAggregateGalleryDownloadBoundUnderParallelPageReads() {
        byte[] page = new byte[24];
        page[0] = (byte) 0xff;
        page[1] = (byte) 0xd8;
        page[2] = (byte) 0xff;
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().equals("/game/doc-1234.html")) {
                        String html = """
                                <div id="preview_imgs">
                                  <img src="https://oss.gstonegames.com/static/image/document/page-01.jpg">
                                  <img src="https://oss.gstonegames.com/static/image/document/page-02.jpg">
                                </div>
                                """;
                        return response(chain.request(), "text/html; charset=utf-8",
                                html.getBytes(StandardCharsets.UTF_8));
                    }
                    return response(chain.request(), "image/jpeg", page);
                })
                .build();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                32,
                8 * 1024,
                pages -> { throw new AssertionError("oversized gallery must not be assembled"); });

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://www.gstonegames.com/game/doc-1234.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page images exceed");
    }

    @Test
    void rejectsAGalleryWhenTheAssembledPdfExceedsTheConfiguredLimit() {
        byte[] jpegMagic = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (calls.getAndIncrement() == 0) {
                        String html = """
                                <div id="preview_imgs">
                                  <img src="https://oss.gstonegames.com/static/image/document/page-01.jpg">
                                  <img src="https://oss.gstonegames.com/static/image/document/page-02.jpg">
                                </div>
                                """;
                        return response(chain.request(), "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                    }
                    return response(chain.request(), "image/jpeg", jpegMagic);
                })
                .build();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                32,
                8 * 1024,
                pages -> {
                    byte[] oversizedPdf = new byte[33];
                    System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, oversizedPdf, 0, 5);
                    return new com.rulepilot.document.application.PhotographedRulebookAssembler.AssembledRulebook(
                            "rulebook.pdf", oversizedPdf);
                });

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://www.gstonegames.com/game/doc-1234.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assembled rulebook PDF exceeds");
        assertThat(calls).hasValue(3);
    }

    @Test
    void requiresPdfMimeMagicAndSize() {
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(
                new OkHttpClient(), 12);

        assertThatThrownBy(() -> fetcher.validatePdf("not a pdf".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a PDF");
        assertThatThrownBy(() -> fetcher.validatePdf("%PDF-12345678".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void compressesABoundedOversizedDirectPdfBeforeFinalVerification() {
        byte[] oversized = "%PDF-123456789012345".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger compressions = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain.request(), "application/pdf", oversized))
                .build();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                16,
                32,
                8 * 1024,
                pages -> { throw new AssertionError("gallery assembly must not run"); },
                (source, maximum) -> {
                    compressions.incrementAndGet();
                    assertThat(source).isEqualTo(oversized);
                    assertThat(maximum).isEqualTo(16);
                    return "%PDF-compressed".getBytes(StandardCharsets.US_ASCII);
                });
        List<String> events = new ArrayList<>();

        var fetched = fetcher.fetch(
                URI.create("https://publisher.example/large-rules.pdf"),
                new OfficialRulebookSourceFetcher.ProgressListener() {
                    @Override public void downloadStarted(Long totalBytes) { events.add("download"); }
                    @Override public void downloaded(long downloadedBytes, Long totalBytes) { events.add("bytes"); }
                    @Override public void downloadCompleted() { events.add("download-complete"); }
                    @Override public void compressing() { events.add("compress"); }
                    @Override public void verifying() { events.add("verify"); }
                });

        assertThat(compressions).hasValue(1);
        assertThat(new String(fetched.content(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-compressed");
        assertThat(events).containsSubsequence("download", "bytes", "download-complete", "compress", "verify");
    }

    @Test
    void rejectsADirectPdfBeyondTheSeparateCompressionInputLimit() {
        byte[] oversized = "%PDF-123456789012345678901234567890".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger compressions = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain.request(), "application/pdf", oversized))
                .build();
        var fetcher = new HttpOfficialRulebookSourceFetcher(
                http,
                16,
                24,
                8 * 1024,
                pages -> { throw new AssertionError("gallery assembly must not run"); },
                (source, maximum) -> {
                    compressions.incrementAndGet();
                    return source;
                });

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://publisher.example/huge-rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe compression input limit");
        assertThat(compressions).hasValue(0);
    }

    @Test
    void downloadsPdfContentEvenWhenTheServerLabelsItAsHtml() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html")
                        .body(ResponseBody.create("%PDF-1.7\nbody", MediaType.parse("text/html")))
                        .build())
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 1_024);

        assertThat(fetcher.fetch(URI.create("https://publisher.example/rules.pdf")).content())
                .isEqualTo("%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] jpeg(int rgb) throws IOException {
        var image = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(rgb));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
        return output.toByteArray();
    }

    private Response response(okhttp3.Request request, String contentType, byte[] body) {
        MediaType mediaType = MediaType.get(contentType);
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", contentType)
                .body(ResponseBody.create(body, mediaType))
                .build();
    }
}
