package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class HttpOfficialRulebookSourceFetcherTest {

    private static final MediaType PDF = MediaType.parse("application/pdf");

    @Test
    void followsOnlyValidatedRedirectsAndReturnsABoundedPdf() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    int call = calls.getAndIncrement();
                    assertThat(chain.request().header("Accept")).isEqualTo("application/pdf");
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
    void rejectsNonPublicTransportAndBggScrapingTargets() {
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
        assertThatThrownBy(() -> fetcher.redirectTarget(
                        URI.create("https://publisher.example/rules.pdf"), "http://127.0.0.1/internal"))
                .isInstanceOf(IllegalArgumentException.class);
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
    void rejectsHtmlEvenWhenTheBodyStartsLikeAPdf() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html")
                        .body(ResponseBody.create("%PDF-fake", MediaType.parse("text/html")))
                        .build())
                .build();
        HttpOfficialRulebookSourceFetcher fetcher = new HttpOfficialRulebookSourceFetcher(http, 1_024);

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://publisher.example/rules.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application/pdf");
    }
}
