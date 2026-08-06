package com.rulepilot.document.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class QwenOfficialRulebookCandidateFinder implements OfficialRulebookCandidateFinder {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final Call.Factory calls;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    @Autowired
    public QwenOfficialRulebookCandidateFinder(
            ObjectMapper json,
            @Value("${rulepilot.models.qwen.enabled:false}") boolean enabled,
            @Value("${rulepilot.models.qwen.api-key:}") String apiKey,
            @Value("${rulepilot.models.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${rulepilot.rulebook-discovery.model:qwen-plus}") String model,
            @Value("${rulepilot.rulebook-discovery.timeout:PT30S}") Duration timeout) {
        this(new OkHttpClient.Builder()
                        .connectTimeout(Math.min(timeout.toMillis(), 5_000), TimeUnit.MILLISECONDS)
                        .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .build(),
                json,
                enabled,
                apiKey,
                secureBaseUrl(baseUrl),
                model);
    }

    QwenOfficialRulebookCandidateFinder(
            Call.Factory calls, ObjectMapper json, boolean enabled, String apiKey, String baseUrl, String model) {
        this.calls = calls;
        this.json = json;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.endpoint = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
        this.model = model;
    }

    @Override
    public boolean configured() {
        return enabled && !apiKey.isBlank();
    }

    @Override
    public List<Candidate> find(OfficialRulebookCandidateFinder.Request request) {
        if (!configured()) return List.of();
        try {
            byte[] body = json.writeValueAsBytes(Map.of(
                    "model", model,
                    "enable_search", true,
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt()),
                            Map.of("role", "user", "content", userPrompt(request)))));
            okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = calls.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("official rulebook discovery failed with status " + response.code());
                }
                byte[] responseBytes = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (responseBytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("official rulebook discovery response is too large");
                }
                JsonNode root = json.readTree(responseBytes);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                return parseCandidates(content);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("official rulebook discovery is temporarily unavailable", exception);
        }
    }

    private List<Candidate> parseCandidates(String content) throws IOException {
        String cleaned = content.strip();
        if (cleaned.startsWith("```")) {
            int firstBreak = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            cleaned = firstBreak >= 0 && lastFence > firstBreak ? cleaned.substring(firstBreak + 1, lastFence).strip() : "";
        }
        JsonNode candidates = json.readTree(cleaned).path("candidates");
        if (!candidates.isArray()) return List.of();
        List<Candidate> result = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            if (result.size() == 8) break;
            result.add(new Candidate(
                    candidate.path("title").asText(""),
                    candidate.path("url").asText(""),
                    candidate.path("publisher").asText(""),
                    candidate.path("language").asText(""),
                    candidate.path("edition").asText("")));
        }
        return List.copyOf(result);
    }

    private String systemPrompt() {
        return "Search the web for official publisher-hosted board-game rulebook PDFs. Return JSON only as "
                + "{\"candidates\":[{\"title\":\"\",\"url\":\"https://...pdf\",\"publisher\":\"\","
                + "\"language\":\"\",\"edition\":\"\"}]}. Do not include BGG files, community uploads, stores, "
                + "mirrors, summaries, HTML pages, or non-PDF URLs. Never invent a URL. Return at most eight candidates.";
    }

    private String userPrompt(OfficialRulebookCandidateFinder.Request request) {
        return "Game: " + request.gameName() + "\nEdition: " + request.editionName() + "\nPublication year: "
                + (request.publicationYear() == null ? "unknown" : request.publicationYear()) + "\nPreferred language: "
                + request.language();
    }

    private static String secureBaseUrl(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Qwen base URL must be HTTPS without credentials");
        }
        return uri.toASCIIString();
    }
}
