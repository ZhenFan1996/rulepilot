package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class HttpGstoneRulebookCatalogLookupTest {

    @Test
    void findsOnlyAnExactAppSearchNameWithoutCredentials() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    assertThat(chain.request().method()).isEqualTo("POST");
                    assertThat(chain.request().url().encodedPath()).isEqualTo("/app/search_game_by_content/");
                    assertThat(chain.request().header("Authorization")).isNull();
                    assertThat(chain.request().header("Cookie")).isNull();
                    assertThat(chain.request().body()).isNotNull();
                    var requestBody = new Buffer();
                    chain.request().body().writeTo(requestBody);
                    assertThat(requestBody.readUtf8()).contains("\"content\":\"目录游戏\"", "\"page\":1");
                    String body = """
                            {
                              "status": 200,
                              "data": {
                                "game_list": [
                                  {"id": 1000, "sch_name": "目录游戏扩展", "eng_name": "Catalog Game Expansion"},
                                  {"id": "9999", "sch_name": "目录游戏", "eng_name": "Catalog Game"},
                                  {"id": 1234, "sch_name": "目录游戏", "eng_name": "Catalog Game"}
                                ],
                                "sql": "untrusted and intentionally ignored"
                              }
                            }
                            """;
                    return json(chain.request(), body);
                })
                .build();
        var lookup = new HttpGstoneRulebookCatalogLookup(http, true);

        var candidates = lookup.find(new OfficialRulebookCandidateFinder.Request(
                42,
                "目录游戏",
                "基础版",
                2024,
                "zh-CN",
                List.of("Catalog Game", "目录游戏"),
                List.of(),
                List.of()));

        assertThat(calls).hasValue(1);
        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.title()).isEqualTo("目录游戏 · 集石规则页");
                    assertThat(candidate.url()).isEqualTo("https://www.gstonegames.com/game/info-1234.html");
                    assertThat(candidate.language()).isEqualTo("zh-CN");
                });
    }

    @Test
    void fallsBackToPublicHtmlSurfacesOnlyForChineseCatalogContext() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        assertThat(chain.request().url().encodedPath())
                                .isEqualTo("/app/search_game_by_content/");
                        return json(chain.request(), "{\"status\":200,\"data\":{\"game_list\":[]}}");
                    }
                    String html = call == 1
                            ? "<!doctype html><html><body><a href='/game/info-9.html'>Other Game</a></body></html>"
                            : "<!doctype html><html><body><a href='https://attacker.example/game/info-4321.html'>目录游戏</a><a href='/game/info-4321.html'>目录游戏</a></body></html>";
                    return html(chain.request(), html);
                })
                .build();
        var lookup = new HttpGstoneRulebookCatalogLookup(http, true);

        assertThat(lookup.find(new OfficialRulebookCandidateFinder.Request(
                        42, "目录游戏", "基础版", 2024, "zh-CN")))
                .singleElement()
                .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                .isEqualTo("https://www.gstonegames.com/game/info-4321.html");
        assertThat(calls).hasValue(3);
    }

    @Test
    void searchesThePublicAppByEnglishNameButDoesNotCrawlChineseHtmlFallback() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient hit = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return json(chain.request(), """
                            {"status":200,"data":{"game_list":[
                              {"id":29568,"sch_name":"方舟动物园","eng_name":"Ark Nova"}
                            ]}}
                            """);
                })
                .build();
        var lookup = new HttpGstoneRulebookCatalogLookup(hit, true);

        assertThat(lookup.find(new OfficialRulebookCandidateFinder.Request(
                        42, "Ark Nova", "Base", 2021, "en")))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.title()).isEqualTo("Ark Nova · 集石规则页");
                    assertThat(candidate.url()).isEqualTo("https://www.gstonegames.com/game/info-29568.html");
                });
        assertThat(calls).hasValue(1);

        AtomicInteger misses = new AtomicInteger();
        OkHttpClient miss = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    misses.incrementAndGet();
                    return json(chain.request(), """
                            {"status":200,"data":{"game_list":[
                              {"id":"29568","sch_name":"方舟动物园","eng_name":"Ark Nova"},
                              {"id":10000000,"sch_name":"方舟动物园","eng_name":"Ark Nova"}
                            ]}}
                            """);
                })
                .build();

        assertThat(new HttpGstoneRulebookCatalogLookup(miss, true)
                        .find(new OfficialRulebookCandidateFinder.Request(
                                42, "Ark Nova", "Base", 2021, "en")))
                .isEmpty();
        assertThat(misses).hasValue(1);
    }

    @Test
    void findsAConfiguredExactGameOnTheCurrentPublicGstoneSurfaces() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_GSTONE_CATALOG_EVAL")));
        String gameName = System.getenv("RULEBOOK_GSTONE_GAME_NAME");
        String expectedUrl = System.getenv("RULEBOOK_GSTONE_EXPECTED_URL");
        assumeTrue(gameName != null && !gameName.isBlank());
        assumeTrue(expectedUrl != null && !expectedUrl.isBlank());
        var lookup = new HttpGstoneRulebookCatalogLookup(new OkHttpClient(), true);

        assertThat(lookup.find(new OfficialRulebookCandidateFinder.Request(
                        42, gameName, "基础版", 2024, "zh-CN")))
                .extracting(OfficialRulebookCandidateFinder.Candidate::url)
                .containsExactly(expectedUrl);
    }

    private Response html(okhttp3.Request request, String html) {
        MediaType mediaType = MediaType.get("text/html; charset=utf-8");
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", mediaType.toString())
                .body(ResponseBody.create(html.getBytes(StandardCharsets.UTF_8), mediaType))
                .build();
    }

    private Response json(okhttp3.Request request, String json) {
        MediaType mediaType = MediaType.get("text/plain; charset=utf-8");
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", mediaType.toString())
                .body(ResponseBody.create(json.getBytes(StandardCharsets.UTF_8), mediaType))
                .build();
    }
}
