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
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import io.micrometer.observation.ObservationRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void recordsARealDensePageWithBoundRuleGroupsAndDiscriminatedQuantities() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_VISUAL_TEACHING_CATALOG_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path pdf = root.resolve(environmentOrDefault(
                "RULEPILOT_VISUAL_TEACHING_CATALOG_CANARY_PDF",
                ".local/public-corpus/pdfs/calico.pdf"));
        int pageNumber = Integer.parseInt(environmentOrDefault(
                "RULEPILOT_VISUAL_TEACHING_CATALOG_CANARY_PAGE", "5"));
        assumeTrue(Files.isRegularFile(pdf), "ignored real visual rulebook is required");

        List<String> rawResponses = new java.util.ArrayList<>();
        String modelName = requiredEnvironment("QWEN_MODEL");
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(
                        "qwen",
                        requiredEnvironment("QWEN_API_KEY"),
                        requiredEnvironment("QWEN_BASE_URL"),
                        modelName);
        ChatModel recording = recording(delegate, rawResponses);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.usesFake(Role.VISUAL, OWNER)).thenReturn(false);
        when(configuration.supportsVision(Role.VISUAL, OWNER)).thenReturn(true);
        when(configuration.providerFor(Role.VISUAL, OWNER)).thenReturn("qwen");
        when(configuration.modelNameFor(Role.VISUAL, OWNER)).thenReturn(modelName);
        when(configuration.modelFor(Role.VISUAL, OWNER)).thenReturn(recording);
        SpringAiVisualRulebookPageCatalogModel catalog = model(configuration);

        long started = System.nanoTime();
        PageSummary page = catalog.summarizeForTeaching(new CatalogRequest(
                        List.of(new PageImageInput(pageNumber, "image/jpeg", render(pdf, pageNumber))),
                        OWNER,
                        "Real rulebook canary"))
                .pages()
                .getFirst();
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(rawResponses).hasSizeBetween(1, 2);
        JsonNode rawPage = rawJson(rawResponses.getLast()).path("pages").get(0);
        assertThat(rawPage.path("ruleGroups").isArray()).isTrue();
        assertThat(rawPage.has("ruleGroupIdentifiers")).isFalse();
        assertThat(rawPage.path("ruleGroups").size()).isBetween(2, 16);
        assertThat(rawPage.path("quantityObservations").isArray()).isTrue();
        assertThat(rawPage.path("quantityObservations").isEmpty()).isFalse();
        rawPage.path("quantityObservations").forEach(observation -> {
            assertThat(observation.has("ruleGroupIndex")).isTrue();
            assertThat(observation.has("ruleGroupIdentifier")).isFalse();
            assertThat(observation.path("kind").asText()).isIn(
                    "PER_VARIANT_EXACT", "TOTAL_EXACT", "REQUIRES_PAGE_INSPECTION");
            assertThat(observation.has("quantifierScope")).isFalse();
            assertThat(observation.has("resolution")).isFalse();
        });
        assertThat(rawPage.path("ruleGroupInventoryComplete").asBoolean()).isTrue();

        List<String> rawIdentifiers = java.util.stream.StreamSupport.stream(
                        rawPage.path("ruleGroups").spliterator(), false)
                .map(group -> group.path("identifier").asText())
                .toList();
        assertThat(page.ruleGroupIdentifiers()).containsExactlyElementsOf(rawIdentifiers);
        assertThat(page.ruleGroupInventoryComplete()).isTrue();
        rawPage.path("ruleGroups").forEach(group -> assertThat(page.factualSummary())
                .contains(group.path("identifier").asText() + ": " + group.path("fact").asText()));
        assertThat(page.quantityObservations()).allSatisfy(observation -> {
            if (observation.quantifierScope() == QuantifierScope.UNRESOLVED) {
                assertThat(observation.resolution()).isEqualTo(QuantityResolution.REQUIRES_PAGE_INSPECTION);
                assertThat(observation.variantCount()).isNull();
                assertThat(observation.perVariantQuantity()).isNull();
                assertThat(observation.derivedTotal()).isNull();
            } else {
                assertThat(observation.resolution()).isEqualTo(QuantityResolution.EXACT);
            }
        });
        assertThat(latencyMs).isLessThan(90_000L);

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("generatedAt", Instant.now().toString());
        artifact.put("provider", "qwen");
        artifact.put("configuredModel", modelName);
        artifact.put("pageNumber", pageNumber);
        artifact.put("modelCalls", rawResponses.size());
        artifact.put("latencyMs", latencyMs);
        artifact.put("rawResponses", rawResponses.stream().map(this::rawJson).toList());
        artifact.put("ruleGroupCount", page.ruleGroupIdentifiers().size());
        artifact.put("quantityObservationCount", page.quantityObservations().size());
        artifact.put("ruleGroupInventoryComplete", page.ruleGroupInventoryComplete());
        artifact.put("parallelIdentifierArrayAbsent", !rawPage.has("ruleGroupIdentifiers"));
        artifact.put("quantityGroupsBoundByIndex", true);
        artifact.put("rawPairsProjectedExactly", true);
        Files.createDirectories(root.resolve(".local/agent-evaluation"));
        Files.writeString(
                root.resolve(".local/agent-evaluation/visual-teaching-catalog-v5-paid-canary.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact) + "\n",
                StandardCharsets.UTF_8);
    }

    private SpringAiVisualRulebookPageCatalogModel model(RuntimeModelConfiguration configuration) throws Exception {
        return new SpringAiVisualRulebookPageCatalogModel(
                configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new ClassPathResource("prompts/visual-page-catalog-v2-icon-inventory-system.txt"),
                new ClassPathResource("prompts/visual-page-teaching-catalog-v5-discriminated-quantities-system.txt"),
                new ClassPathResource("prompts/visual-page-progressive-teaching-start-v4-source-contract-system.txt"),
                new ClassPathResource("prompts/visual-icon-localization-v2-system.txt"),
                new ClassPathResource("prompts/visual-icon-crop-review-v4-system.txt"),
                new ClassPathResource("prompts/visual-identifier-cell-v1-system.txt"),
                new ClassPathResource("prompts/visual-identifier-reference-match-v1-system.txt"),
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

    private byte[] render(Path pdf, int pageNumber) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, 144, ImageType.RGB);
            assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
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
