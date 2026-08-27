package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.testing.PaidCanaryTrace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

@Tag("paid-visual-teaching-catalog-canary")
class VisualTeachingCatalogPaidCanaryTest {

    private static final String OWNER = "agent-evaluation";
    private static final Pattern GSTONE_DOCUMENT_IMAGE = Pattern.compile("data-original=\\\"(//[^\\\"]+)\\\"");
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void catalogsOneRealGstonePageWithQuantityLineage() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_GSTONE_VISUAL_PAGE_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        int pageNumber = Integer.parseInt(environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_PAGE_NUMBER", "16"));
        String documentUrl = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_URL",
                "https://www.gstonegames.com/game/doc-5689.html");
        final String rulebookTitle = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_TITLE", "奋进号：深海");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        List<URI> pageImages = gstonePageImages(http, documentUrl);
        assertThat(pageNumber).isBetween(1, pageImages.size());
        byte[] image = rgbJpeg(fetchBytes(http, pageImages.get(pageNumber - 1)));

        try (PaidCanaryTrace trace = PaidCanaryTrace.start("teaching-visual-page")) {
            String modelName = environmentOrRequired(
                    "RULEPILOT_GSTONE_VISUAL_PAGE_MODEL", "QWEN_MODEL");
            List<String> rawResponses = new ArrayList<>();
            ChatModel delegate = new ChatModelFactory(trace.observations(), Duration.ofSeconds(120))
                    .create(
                            "qwen",
                            requiredEnvironment("QWEN_API_KEY"),
                            requiredEnvironment("QWEN_BASE_URL"),
                            modelName);
            RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
            when(configuration.usesFake(Role.VISUAL, OWNER)).thenReturn(false);
            when(configuration.supportsVision(Role.VISUAL, OWNER)).thenReturn(true);
            when(configuration.providerFor(Role.VISUAL, OWNER)).thenReturn("qwen");
            when(configuration.modelNameFor(Role.VISUAL, OWNER)).thenReturn(modelName);
            when(configuration.modelFor(Role.VISUAL, OWNER)).thenReturn(recording(delegate, rawResponses));
            SpringAiVisualRulebookPageCatalogModel catalog = model(configuration);
            var executionIdentity = catalog.teachingStartupExecutionIdentity(OWNER).orElseThrow();

            long started = System.nanoTime();
            long semanticLatencyMs = -1L;
            PageSummary page;
            try {
                PageImageInput pageImage = new PageImageInput(pageNumber, "image/jpeg", image);
                long semanticStarted = System.nanoTime();
                page = trace.observe(
                                "teaching-visual-semantic-catalog",
                                () -> catalog.summarizeForTeaching(new CatalogRequest(
                                        List.of(pageImage),
                                        OWNER,
                                        rulebookTitle)))
                        .pages()
                        .getFirst();
                semanticLatencyMs = Duration.ofNanos(System.nanoTime() - semanticStarted).toMillis();
            } catch (RuntimeException failure) {
                trace.recordFailure(failure);
                Map<String, Object> failedArtifact = new LinkedHashMap<>();
                failedArtifact.put("schemaVersion", 1);
                failedArtifact.put("generatedAt", Instant.now().toString());
                failedArtifact.put("sourceUrl", documentUrl);
                failedArtifact.put("pageNumber", pageNumber);
                failedArtifact.put("provider", executionIdentity.provider());
                failedArtifact.put("configuredModel", modelName);
                failedArtifact.put("model", executionIdentity.model());
                failedArtifact.put("traceId", trace.traceId());
                failedArtifact.put("modelCalls", rawResponses.size());
                failedArtifact.put("semanticCatalogLatencyMs", semanticLatencyMs);
                failedArtifact.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                failedArtifact.put("failure", failure.toString());
                failedArtifact.put("rawResponses", rawResponses.stream().map(this::rawJsonOrText).toList());
                writeArtifact(
                        root.resolve(".local/agent-evaluation/gstone-visual-page-" + pageNumber + "-canary.json"),
                        failedArtifact);
                throw failure;
            }
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(page.pageNumber()).isEqualTo(pageNumber);
            assertThat(page.ruleGroupInventoryComplete()).isTrue();
            assertThat(page.ruleGroupIdentifiers()).isNotEmpty();
            assertThat(rawResponses).hasSize(1);

            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("schemaVersion", 1);
            artifact.put("generatedAt", Instant.now().toString());
            artifact.put("sourceUrl", documentUrl);
            artifact.put("pageNumber", pageNumber);
            artifact.put("provider", executionIdentity.provider());
            artifact.put("configuredModel", modelName);
            artifact.put("model", executionIdentity.model());
            artifact.put("traceId", trace.traceId());
            artifact.put("modelCalls", rawResponses.size());
            artifact.put("latencyMs", latencyMs);
            artifact.put("semanticCatalogLatencyMs", semanticLatencyMs);
            artifact.put("rawResponses", rawResponses.stream().map(this::rawJsonOrText).toList());
            artifact.put("pageSummary", json.valueToTree(page));
            writeArtifact(
                    root.resolve(".local/agent-evaluation/gstone-visual-page-" + pageNumber + "-canary.json"),
                    artifact);
        }
    }

    private String environmentOrRequired(String preferredName, String requiredName) {
        String preferred = System.getenv(preferredName);
        return preferred == null || preferred.isBlank() ? requiredEnvironment(requiredName) : preferred.strip();
    }

    private SpringAiVisualRulebookPageCatalogModel model(RuntimeModelConfiguration configuration) throws Exception {
        return new SpringAiVisualRulebookPageCatalogModel(
                configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new ClassPathResource("prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt"),
                4_800);
    }


    private ChatModel recording(ChatModel delegate, List<String> rawResponses) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                ChatResponse response = delegate.call(prompt);
                String content = response == null || response.getResult() == null
                                || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                rawResponses.add(content == null ? "" : content);
                return response;
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return delegate.getDefaultOptions();
            }

            @Override
            public ChatOptions getOptions() {
                return delegate.getOptions();
            }
        };
    }


    private void writeArtifact(Path artifactPath, Map<String, Object> artifact) throws Exception {
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(
                artifactPath,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact) + "\n",
                StandardCharsets.UTF_8);
    }

    private JsonNode rawJson(String content) {
        try {
            String value = content == null ? "" : content.strip();
            if (value.startsWith("```")) {
                value = value.substring(value.indexOf('\n') + 1, value.lastIndexOf("```")).strip();
            }
            return json.readTree(value);
        } catch (Exception failure) {
            throw new AssertionError("paid visual model did not return readable JSON", failure);
        }
    }

    private Object rawJsonOrText(String content) {
        try {
            return rawJson(content);
        } catch (AssertionError incompleteJson) {
            return Map.of("unparsedProviderText", content == null ? "" : content);
        }
    }


    private List<URI> gstonePageImages(HttpClient http, String documentUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(documentUrl))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "RulePilot real visual teaching canary")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = GSTONE_DOCUMENT_IMAGE.matcher(response.body());
        LinkedHashSet<URI> images = new LinkedHashSet<>();
        while (matcher.find()) images.add(URI.create("https:" + matcher.group(1)));
        return List.copyOf(images);
    }

    private byte[] fetchBytes(HttpClient http, URI image) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(image)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "RulePilot real visual teaching canary")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        return response.body();
    }

    private byte[] rgbJpeg(byte[] source) throws Exception {
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(source));
        assertThat(decoded).isNotNull();
        BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.drawImage(decoded, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(rgb, "jpeg", output)).isTrue();
            return output.toByteArray();
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new AssertionError(name + " is required for the paid canary");
        return value;
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

}
