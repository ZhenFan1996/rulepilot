package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.application.OfficialRulebookSourceInspector;
import com.rulepilot.document.application.OfficialRulebookSourceInspector.PageSignal;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class HttpOfficialRulebookSourceInspectorTest {

    private static final MediaType HTML = MediaType.get("text/html; charset=utf-8");
    private static final MediaType PDF = MediaType.get("application/pdf");

    @Test
    void extractsBoundedPublicLinksFromAnObservedPublisherPageWithoutForwardingCredentials() {
        String html = """
                <!doctype html><html><body>
                  <a href="/api/download/C0096_en-us_acquire.pdf">Download</a>
                  <a href="https://cdn.publisher.example/catalog-game/rules/">Game Rules</a>
                  <a href="https://docs.publisher.example/privacy.pdf">Privacy policy</a>
                  <a href="http://publisher.example/insecure.pdf">Insecure</a>
                  <a href="mailto:help@publisher.example">Email</a>
                </body></html>
                """;
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    assertThat(chain.request().header("Authorization")).isNull();
                    assertThat(chain.request().header("Cookie")).isNull();
                    return response(chain.request(), 200, "OK", HTML, html.getBytes(StandardCharsets.UTF_8));
                })
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        var inspection = inspector.inspect(URI.create("https://publisher.example/game/acquire")).orElseThrow();

        assertThat(inspection.mediaType()).isEqualTo(OfficialRulebookSourceInspector.MediaType.HTML);
        assertThat(inspection.links()).extracting(link -> link.target().toASCIIString()).containsExactly(
                "https://publisher.example/api/download/C0096_en-us_acquire.pdf",
                "https://cdn.publisher.example/catalog-game/rules/",
                "https://docs.publisher.example/privacy.pdf");
        assertThat(inspection.links()).extracting(OfficialRulebookSourceInspector.Link::label)
                .containsExactly("Download", "Game Rules", "Privacy policy");
    }

    @Test
    void identifiesSuffixlessPdfResponsesByMimeOrMagicWithoutReadingTheWholeFile() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> calls.getAndIncrement() == 0
                        ? response(chain.request(), 200, "OK", PDF, "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII))
                        : response(
                                chain.request(),
                                200,
                                "OK",
                                MediaType.get("application/octet-stream"),
                                "%PDF-2.0\nbody".getBytes(StandardCharsets.US_ASCII)))
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/download?id=42")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::mediaType)
                .isEqualTo(OfficialRulebookSourceInspector.MediaType.PDF);
        assertThat(inspector.inspect(URI.create("https://cdn.publisher.example/attachment/42")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::mediaType)
                .isEqualTo(OfficialRulebookSourceInspector.MediaType.PDF);
    }

    @Test
    void identifiesOnlyTheOrderedImagesInsideAGstoneDocumentViewerAsAnImportableGallery() {
        String html = """
                <!doctype html><html><head><title>官方规则书</title></head><body>
                  <div id="preview_imgs">
                    <p><img data-original="//oss.gstonegames.com/static/image/document/page-01.jpg"></p>
                    <p><img data-original="//oss.gstonegames.com/static/image/document/page-02.jpg"></p>
                  </div>
                  <img src="//oss.gstonegames.com/static/image/banner/banner.jpg">
                </body></html>
                """;
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(
                        chain.request(), 200, "OK", HTML, html.getBytes(StandardCharsets.UTF_8)))
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        var inspection = inspector.inspect(
                URI.create("https://www.gstonegames.com/game/doc-1234.html")).orElseThrow();

        assertThat(inspection.mediaType()).isEqualTo(OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY);
        assertThat(inspection.links()).isEmpty();
    }

    @Test
    void retainsExplicitRulebookLinksThatAppearAfterTheGenericLinkBudget() {
        StringBuilder html = new StringBuilder("<!doctype html><html><body>");
        for (int index = 0; index < 90; index++) {
            html.append("<a href=\"/article/").append(index).append("\">Article ").append(index).append("</a>");
        }
        html.append("<a href=\"/game/doc-37.html\">卡坦岛官方中文规则书</a></body></html>");
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(
                        chain.request(),
                        200,
                        "OK",
                        HTML,
                        html.toString().getBytes(StandardCharsets.UTF_8)))
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        var inspection = inspector.inspect(
                URI.create("https://www.gstonegames.com/game/info-545.html")).orElseThrow();

        assertThat(inspection.links()).hasSize(80);
        assertThat(inspection.links().getFirst().target())
                .isEqualTo(URI.create("https://www.gstonegames.com/game/doc-37.html"));
    }

    @Test
    void followsOnlyBoundedPublicHttpsRedirectsAndNeverReadsBggHtml() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "http://127.0.0.1/internal")
                            .body(ResponseBody.create(new byte[0], null))
                            .build();
                })
                .followRedirects(false)
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/rules"))).isEmpty();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> inspector.inspect(URI.create("https://boardgamegeek.com/filepage/42/rules")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BGG");
        assertThatThrownBy(() -> inspector.inspect(URI.create("http://publisher.example/rules")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void preservesLoginRequiredWhenAPublicSourceRedirectsToSignIn() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (calls.getAndIncrement() == 0) {
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(302)
                                .message("Found")
                                .header("Location", "https://publisher.example/sign-in")
                                .body(ResponseBody.create(new byte[0], null))
                                .build();
                    }
                    return response(
                            chain.request(),
                            200,
                            "OK",
                            HTML,
                            "<html><form><input type=password></form></html>"
                                    .getBytes(StandardCharsets.UTF_8));
                })
                .followRedirects(false)
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/rules")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::pageSignals)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(PageSignal.class))
                .containsExactly(PageSignal.LOGIN_REQUIRED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void reportsProtocolAndStructuredPageSignalsWithoutUsingTheCandidateTitle() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    String path = chain.request().url().encodedPath();
                    String html = switch (path) {
                        case "/documents" -> """
                                <!doctype html><html><body>
                                  <main><a download href="/asset/opaque.bin" type="application/pdf">Get file</a></main>
                                </body></html>
                                """;
                        case "/game" -> """
                                <!doctype html><html><head>
                                  <script type="application/ld+json">{"@type":"Game","name":"Opaque Atlas"}</script>
                                </head><body>
                                  <main><section data-document-count="0"><h2>Documents</h2><ul></ul></section></main>
                                </body></html>
                                """;
                        case "/login" -> """
                                <!doctype html><html><body><main><form><input type="password"></form></main></body></html>
                                """;
                        default -> throw new AssertionError("unexpected path " + path);
                    };
                    return response(chain.request(), 200, "OK", HTML, html.getBytes(StandardCharsets.UTF_8));
                })
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/documents")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::pageSignals)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(PageSignal.class))
                .containsExactly(PageSignal.DOWNLOADABLE_DOCUMENT_LINKS);
        assertThat(inspector.inspect(URI.create("https://publisher.example/game")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::pageSignals)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(PageSignal.class))
                .containsExactlyInAnyOrder(
                        PageSignal.EXPLICIT_EMPTY_DOCUMENT_COLLECTION,
                        PageSignal.STRUCTURED_GAME_INFORMATION);
        assertThat(inspector.inspect(URI.create("https://publisher.example/login")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::pageSignals)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(PageSignal.class))
                .containsExactly(PageSignal.LOGIN_REQUIRED);
    }

    @Test
    void acceptsOnlyParsedExactStructuredTypesAsGameInformationEvidence() {
        String html = """
                <!doctype html><html><head>
                  <script type="application/ld+json">not-json {"@type":"Game"}</script>
                  <meta itemprop="numberOfItems" content="0">
                </head><body>
                  <main itemtype="https://example.com/NotAGame"></main>
                </body></html>
                """;
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(
                        chain.request(), 200, "OK", HTML, html.getBytes(StandardCharsets.UTF_8)))
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/catalog-entry")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::pageSignals)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.set(PageSignal.class))
                .isEmpty();
    }

    @Test
    void doesNotPublishPdfDownloadCapabilityFromAnAttachmentFilenameAlone() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    boolean exactPdf = calls.getAndIncrement() == 0;
                    return response(
                                    chain.request(),
                                    200,
                                    "OK",
                                    exactPdf ? MediaType.get("application/octet-stream") : HTML,
                                    exactPdf
                                            ? "opaque attachment".getBytes(StandardCharsets.UTF_8)
                                            : "<html><body>catalog page</body></html>"
                                                    .getBytes(StandardCharsets.UTF_8))
                            .newBuilder()
                            .header(
                                    "Content-Disposition",
                                    exactPdf
                                            ? "attachment; filename=opaque-rulebook.pdf"
                                            : "attachment; filename=opaque-rulebook.pdf.exe")
                            .build();
                })
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/attachment/1"))).isEmpty();
        assertThat(inspector.inspect(URI.create("https://publisher.example/attachment/2")))
                .get()
                .extracting(OfficialRulebookSourceInspector.Inspection::mediaType)
                .isEqualTo(OfficialRulebookSourceInspector.MediaType.HTML);
    }

    @Test
    void leavesForbiddenAndTimedOutSourcesUnverifiedInsteadOfInferringFromTheirUrls() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().equals("/timeout.pdf")) {
                        throw new SocketTimeoutException("fixture timeout");
                    }
                    return response(
                            chain.request(),
                            403,
                            "Forbidden",
                            HTML,
                            "<html><title>Rules PDF</title></html>".getBytes(StandardCharsets.UTF_8));
                })
                .build();
        var inspector = new HttpOfficialRulebookSourceInspector(http, 64 * 1024);

        assertThat(inspector.inspect(URI.create("https://publisher.example/forbidden.pdf"))).isEmpty();
        assertThat(inspector.inspect(URI.create("https://publisher.example/timeout.pdf"))).isEmpty();
    }

    private Response response(
            okhttp3.Request request, int code, String message, MediaType mediaType, byte[] body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .header("Content-Type", mediaType.toString())
                .body(ResponseBody.create(body, mediaType))
                .build();
    }
}
