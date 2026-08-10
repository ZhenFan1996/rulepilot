package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.document.application.MinioStorageProperties;
import com.rulepilot.document.application.OfficialRulebookCandidateFinder;
import com.rulepilot.document.application.OfficialRulebookSourceFetcher.FetchedRulebook;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("real-rulebook-acquisition-evaluation")
class OfficialRulebookAcquisitionRealEvaluationTest {

    private static final long MAXIMUM_PDF_BYTES = 100L * 1024 * 1024;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void discoversAndDownloadsAnObservedPublisherPdfThroughApplicationValidation() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULEBOOK_ACQUISITION_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path casePath = root.resolve(".local/agent-evaluation/rulebook-acquisition-case.json");
        assumeTrue(Files.isRegularFile(casePath), "ignored real acquisition case is required");
        JsonNode case_ = json.readTree(casePath.toFile());
        assertThat(case_.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(case_.path("caseId").asText()).matches("cx-[a-z0-9-]+");
        assertThat(case_.path("consentGranted").asBoolean()).isTrue();

        String provider = optionalEnvironment("RULEBOOK_DISCOVERY_PROVIDER", "qwen");
        String apiKey = requiredEnvironment("RULEBOOK_DISCOVERY_API_KEY", "QWEN_API_KEY");
        String baseUrl = requiredEnvironment("RULEBOOK_DISCOVERY_BASE_URL", "QWEN_BASE_URL");
        String model = optionalEnvironment(
                "RULEBOOK_DISCOVERY_MODEL",
                optionalEnvironment("QWEN_MODEL", "qwen3.7-plus"));
        assertThat(prohibitedModel(model)).isFalse();

        var finder = new ResponsesApiOfficialRulebookCandidateFinder(
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(120))
                        .callTimeout(Duration.ofSeconds(120))
                        .build(),
                json,
                true,
                apiKey,
                baseUrl,
                model);
        OfficialRulebookCandidateFinder.Request request = request(case_);
        long discoveryStarted = System.nanoTime();
        List<OfficialRulebookCandidateFinder.Candidate> discovered = finder.find(request);
        long discoveryLatencyMs = elapsedMillis(discoveryStarted);
        List<OfficialRulebookCandidateFinder.Candidate> direct = discovered.stream()
                .filter(candidate -> directPdf(candidate.url()))
                .toList();

        assertThat(discovered).isNotEmpty();
        assertThat(direct).isNotEmpty();
        assertThat(discoveryLatencyMs).isLessThanOrEqualTo(120_000L);

        var fetcher = new HttpOfficialRulebookSourceFetcher(
                new MinioStorageProperties(
                        "http://127.0.0.1:9000",
                        "evaluation-access",
                        "evaluation-secret",
                        "rulepilot-evaluation",
                        MAXIMUM_PDF_BYTES),
                Duration.ofSeconds(10),
                Duration.ofSeconds(90),
                Duration.ofMinutes(10));
        FetchedRulebook fetched = null;
        String sha256 = "";
        String expectedSha256 = case_.path("expectedSha256").asText("").strip().toLowerCase(Locale.ROOT);
        int attemptedDownloads = 0;
        long downloadLatencyMs = 0;
        List<String> failureCategories = new ArrayList<>();
        for (OfficialRulebookCandidateFinder.Candidate candidate : direct) {
            attemptedDownloads++;
            long downloadStarted = System.nanoTime();
            try {
                FetchedRulebook candidateFetched = fetcher.fetch(URI.create(candidate.url()));
                String candidateSha256 = digest(candidateFetched.content());
                if (!expectedSha256.isBlank() && !candidateSha256.equals(expectedSha256)) {
                    failureCategories.add("CONTENT_IDENTITY_MISMATCH");
                    continue;
                }
                fetched = candidateFetched;
                sha256 = candidateSha256;
                break;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                failureCategories.add(exception instanceof IllegalArgumentException
                        ? "VALIDATION_REJECTED"
                        : "SOURCE_UNAVAILABLE");
            } finally {
                downloadLatencyMs += elapsedMillis(downloadStarted);
            }
        }
        assertThat(fetched).as("at least one observed direct PDF must pass the application fetcher").isNotNull();
        assertThat(downloadLatencyMs).isLessThanOrEqualTo(600_000L);
        byte[] content = Objects.requireNonNull(fetched).content();
        if (sha256.isBlank()) sha256 = digest(content);
        assertThat(new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.path("caseId").asText());
        result.put("provider", provider);
        result.put("model", model);
        result.put("discoveredCandidateCount", discovered.size());
        result.put("directPdfCandidateCount", direct.size());
        result.put("attemptedDownloads", attemptedDownloads);
        result.put("failedAttemptCategories", failureCategories.stream().distinct().toList());
        result.put("sourceObserved", true);
        result.put("publicHttpsVerified", "https".equalsIgnoreCase(fetched.finalSource().getScheme()));
        result.put("mimeVerified", true);
        result.put("pdfMagicVerified", true);
        result.put("digestRecorded", true);
        result.put("sha256", sha256);
        result.put("finalHostDigest", digest(fetched.finalSource().getHost().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8)));
        result.put("bytes", content.length);
        result.put("discoveryLatencyMs", discoveryLatencyMs);
        result.put("downloadLatencyMs", downloadLatencyMs);

        Path output = root.resolve(".local/agent-evaluation/rulebook-acquisition-real.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", List.of(result),
                "controls", Map.of(
                        "explicitConsentRequired", true,
                        "applicationFetcherUsed", true,
                        "rawPdfStored", false,
                        "rawProviderOutputStored", false,
                        "prohibitedQwenPlusUsed", false))) + "\n", StandardCharsets.UTF_8);
    }

    private OfficialRulebookCandidateFinder.Request request(JsonNode case_) {
        return new OfficialRulebookCandidateFinder.Request(
                case_.path("bggId").asInt(),
                case_.path("gameName").asText(),
                case_.path("editionName").asText(),
                case_.path("publicationYear").isIntegralNumber()
                        ? case_.path("publicationYear").asInt()
                        : null,
                case_.path("language").asText(),
                strings(case_.path("officialNames")),
                strings(case_.path("publishers")),
                strings(case_.path("trustedDomains")));
    }

    private List<String> strings(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().strip());
        });
        return List.copyOf(result);
    }

    private boolean directPdf(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && (path.endsWith(".pdf") || query.contains(".pdf") || path.contains("/file/download_redirect/"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String requiredEnvironment(String primary, String fallback) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) value = System.getenv(fallback);
        assumeTrue(value != null && !value.isBlank(), primary + " or " + fallback + " is required");
        return value.strip();
    }

    private String optionalEnvironment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private boolean prohibitedModel(String model) {
        String normalized = model.toLowerCase(Locale.ROOT);
        return normalized.equals("qwen-plus")
                || normalized.startsWith("qwen-plus-")
                || normalized.startsWith("qwen-plus_");
    }

    private long elapsedMillis(long started) {
        return Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
