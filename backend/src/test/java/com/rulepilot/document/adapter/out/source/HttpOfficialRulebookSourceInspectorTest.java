package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.document.application.OfficialRulebookSourceInspector;
import java.net.URI;
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
