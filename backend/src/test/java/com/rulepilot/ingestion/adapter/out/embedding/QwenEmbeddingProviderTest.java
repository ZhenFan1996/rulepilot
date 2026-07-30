package com.rulepilot.ingestion.adapter.out.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class QwenEmbeddingProviderTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsBoundedOpenAiCompatibleBatchesAndRestoresResponseOrder() {
        AtomicInteger calls = new AtomicInteger();
        List<Map<String, Object>> requests = new ArrayList<>();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Buffer body = new Buffer();
                    chain.request().body().writeTo(body);
                    requests.add(json.readValue(body.readUtf8(), new TypeReference<>() {}));
                    assertThat(chain.request().url().encodedPath()).isEqualTo("/compatible-mode/v1/embeddings");
                    assertThat(chain.request().header("Authorization")).isEqualTo("Bearer local-test-key");
                    int call = calls.getAndIncrement();
                    String response = call == 0
                            ? response(List.of(item(1, 2), item(0, 1)))
                            : response(List.of(item(0, 3)));
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(response, JSON))
                            .build();
                })
                .build();
        var provider = new QwenEmbeddingProvider(properties(2), http, json);

        var vectors = provider.embed(List.of("setup", "turn order", "scoring"));

        assertThat(calls).hasValue(2);
        assertThat(requests).extracting(request -> ((List<?>) request.get("input")).size()).containsExactly(2, 1);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.get("model")).isEqualTo("text-embedding-v4");
            assertThat(request.get("dimensions")).isEqualTo(64);
            assertThat(request.get("encoding_format")).isEqualTo("float");
        });
        assertThat(vectors).extracting(vector -> vector.values().getFirst()).containsExactly(1.0f, 2.0f, 3.0f);
        assertThat(provider.id()).isEqualTo("qwen:text-embedding-v4:64");
    }

    @Test
    void rejectsUnexpectedResponseShapeWithoutLeakingProviderBody() {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}", JSON))
                        .build())
                .build();
        var provider = new QwenEmbeddingProvider(properties(10), http, json);

        assertThatThrownBy(() -> provider.embed(List.of("setup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Qwen embedding response shape is invalid")
                .hasMessageNotContaining("local-test-key");
    }

    @Test
    void requiresHttpsAndSupportedDimensions() {
        assertThatThrownBy(() -> new QwenEmbeddingProperties(
                        "key", "http://example.test/v1", "text-embedding-v4", 1024, Duration.ofSeconds(10), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Qwen embedding base URL must be HTTPS");
        assertThatThrownBy(() -> new QwenEmbeddingProperties(
                        "key", "https://example.test/v1", "text-embedding-v4", 63, Duration.ofSeconds(10), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Qwen embedding configuration is invalid");
    }

    private QwenEmbeddingProperties properties(int batchSize) {
        return new QwenEmbeddingProperties(
                "local-test-key",
                "https://example.test/compatible-mode/v1/",
                "text-embedding-v4",
                64,
                Duration.ofSeconds(10),
                batchSize);
    }

    private Map<String, Object> item(int index, int firstValue) {
        List<Float> values = new ArrayList<>(Collections.nCopies(64, 0.0f));
        values.set(0, (float) firstValue);
        return Map.of("object", "embedding", "index", index, "embedding", values);
    }

    private String response(List<Map<String, Object>> items) {
        try {
            return json.writeValueAsString(Map.of(
                    "object", "list",
                    "model", "text-embedding-v4",
                    "data", items,
                    "usage", Map.of("prompt_tokens", 7, "total_tokens", 7)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
