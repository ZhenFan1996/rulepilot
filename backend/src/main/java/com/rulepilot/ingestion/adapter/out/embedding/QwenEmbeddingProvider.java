package com.rulepilot.ingestion.adapter.out.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.ingestion.EmbeddingProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class QwenEmbeddingProvider implements EmbeddingProvider {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_RESPONSE_BYTES = 4_000_000;

    private final QwenEmbeddingProperties properties;
    private final OkHttpClient http;
    private final ObjectMapper json;
    private final String endpoint;

    QwenEmbeddingProvider(QwenEmbeddingProperties properties, OkHttpClient http, ObjectMapper json) {
        this.properties = properties;
        this.http = http;
        this.json = json;
        this.endpoint = properties.baseUrl() + "/embeddings";
    }

    @Override
    public String id() {
        return properties.providerId();
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    @Override
    public List<EmbeddingVector> embed(List<String> texts) {
        validateInputs(texts);
        List<EmbeddingVector> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            int to = Math.min(texts.size(), from + properties.batchSize());
            result.addAll(embedBatch(texts.subList(from, to)));
        }
        return List.copyOf(result);
    }

    private List<EmbeddingVector> embedBatch(List<String> texts) {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(Map.of(
                    "model", properties.model(),
                    "input", texts,
                    "dimensions", properties.dimensions(),
                    "encoding_format", "float"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Qwen embedding request could not be encoded", exception);
        }
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Accept", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Qwen embedding request failed with status " + response.code());
            }
            byte[] responseBytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("Qwen embedding response is too large");
            }
            return decodeResponse(responseBytes, texts.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Qwen embedding service is temporarily unavailable", exception);
        }
    }

    private List<EmbeddingVector> decodeResponse(byte[] responseBytes, int expectedCount) {
        EmbeddingResponse response;
        try {
            response = json.readValue(responseBytes, EmbeddingResponse.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Qwen embedding response is invalid", exception);
        }
        if (response.data() == null || response.data().size() != expectedCount) {
            throw new IllegalStateException("Qwen embedding response count does not match the request");
        }
        List<EmbeddingData> ordered = response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .toList();
        HashSet<Integer> indices = new HashSet<>();
        List<EmbeddingVector> vectors = new ArrayList<>(expectedCount);
        for (int index = 0; index < ordered.size(); index++) {
            EmbeddingData item = ordered.get(index);
            if (item.index() != index || !indices.add(item.index())
                    || item.embedding() == null || item.embedding().size() != properties.dimensions()) {
                throw new IllegalStateException("Qwen embedding response shape is invalid");
            }
            vectors.add(new EmbeddingVector(item.embedding()));
        }
        return List.copyOf(vectors);
    }

    private void validateInputs(List<String> texts) {
        if (texts == null || texts.isEmpty()
                || texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("embedding input is required");
        }
    }

    private record EmbeddingResponse(List<EmbeddingData> data) {}

    private record EmbeddingData(int index, List<Float> embedding) {}
}
