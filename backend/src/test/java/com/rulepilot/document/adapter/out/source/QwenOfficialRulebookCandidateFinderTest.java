package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class QwenOfficialRulebookCandidateFinderTest {

    @Test
    void enablesWebSearchWithoutLeakingCredentialsIntoTheResult() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String content = json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "title", "Rules",
                    "url", "https://publisher.example/rules.pdf",
                    "publisher", "Publisher",
                    "language", "en",
                    "edition", "First"))));
            byte[] response = json.writeValueAsBytes(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var finder = new QwenOfficialRulebookCandidateFinder(
                    new OkHttpClient(),
                    new ObjectMapper(),
                    true,
                    "secret-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen-plus");

            var candidates = finder.find(new OfficialRulebookCandidateFinder.Request(
                    "Catalog Game", "First", 2024, "en"));

            assertThat(candidates).singleElement().satisfies(candidate -> {
                assertThat(candidate.url()).isEqualTo("https://publisher.example/rules.pdf");
                assertThat(candidate.toString()).doesNotContain("secret-key");
            });
            assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            assertThat(requestBody.get()).contains("\"enable_search\":true", "Catalog Game");
            assertThat(requestBody.get()).doesNotContain("secret-key");
        } finally {
            server.stop(0);
        }
    }
}
