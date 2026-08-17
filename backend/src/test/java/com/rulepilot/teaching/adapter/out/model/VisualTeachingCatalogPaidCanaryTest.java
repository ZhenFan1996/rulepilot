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
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineDraft;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.PageInput;
import com.rulepilot.teaching.VisualQuantityObservation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.CatalogRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageSummary;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageTranscript;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import com.rulepilot.teaching.application.TeachingPlanFactory;
import com.rulepilot.teaching.application.TeachingSourceCoverageContract;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;

@Tag("paid-visual-teaching-catalog-canary")
class VisualTeachingCatalogPaidCanaryTest {

    private static final String OWNER = "agent-evaluation";
    private static final Pattern GSTONE_DOCUMENT_IMAGE = Pattern.compile("data-original=\\\"(//[^\\\"]+)\\\"");
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
        assertThat(rawPage.path("ruleGroups").isEmpty()).isFalse();
        assertThat(rawPage.has("quantityObservations")).isFalse();
        List<JsonNode> rawGroups = java.util.stream.StreamSupport.stream(
                        rawPage.path("ruleGroups").spliterator(), false)
                .toList();
        assertThat(rawGroups).allSatisfy(group -> assertThat(group.path("quantitySpans").isArray()).isTrue());
        assertThat(rawGroups.stream()
                        .flatMap(group -> java.util.stream.StreamSupport.stream(
                                group.path("quantitySpans").spliterator(), false))
                        .toList())
                .isNotEmpty();
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
            assertThat(observation.quantifierScope()).isEqualTo(QuantifierScope.LITERAL_SOURCE_SPAN);
            assertThat(observation.resolution()).isEqualTo(QuantityResolution.TRANSCRIBED_SOURCE_SPAN);
            assertThat(observation.variantCount()).isNull();
            assertThat(observation.perVariantQuantity()).isNull();
            assertThat(observation.derivedTotal()).isNull();
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
        artifact.put("quantitySpansBoundInsideRuleGroups", true);
        artifact.put("rawPairsProjectedExactly", true);
        Files.createDirectories(root.resolve(".local/agent-evaluation"));
        Files.writeString(
                root.resolve(".local/agent-evaluation/visual-teaching-catalog-v5-paid-canary.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void preparesACompleteRealGstoneVisualRulebookWithCompactWholeGamePlanning() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_GSTONE_VISUAL_TEACHING_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path artifactPath = root.resolve(
                ".local/agent-evaluation/gstone-endeavor-visual-teaching-preparation-v1.json");
        boolean reuseVisualCatalog = "true".equalsIgnoreCase(
                System.getenv("RULEPILOT_GSTONE_REUSE_VISUAL_CATALOG"));
        JsonNode priorArtifact = reuseVisualCatalog && Files.isRegularFile(artifactPath)
                ? json.readTree(Files.readString(artifactPath, StandardCharsets.UTF_8))
                : null;
        String documentUrl = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_URL",
                "https://www.gstonegames.com/game/doc-5689.html");
        String rulebookTitle = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_TITLE",
                "奋进号：深海");
        int expectedPages = Integer.parseInt(environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_PAGES", "16"));
        int visualParallelism = Integer.parseInt(environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_PARALLELISM", "3"));
        assertThat(visualParallelism).isBetween(1, 4);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        List<URI> pageImages = gstonePageImages(http, documentUrl);
        assertThat(pageImages).hasSize(expectedPages);

        String visualModelName = requiredEnvironment("QWEN_MODEL");
        List<String> rawCatalogResponses = new CopyOnWriteArrayList<>();
        List<VisualCatalogRawResponse> detailedRawCatalogResponses = new CopyOnWriteArrayList<>();
        List<VisualCatalogAttempt> pageAttempts = new CopyOnWriteArrayList<>();
        ThreadLocal<VisualCallContext> visualCallContext = new ThreadLocal<>();
        AtomicInteger activeVisualRequests = new AtomicInteger();
        AtomicInteger peakVisualRequests = new AtomicInteger();
        ChatModel visualDelegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(
                        "qwen",
                        requiredEnvironment("QWEN_API_KEY"),
                        requiredEnvironment("QWEN_BASE_URL"),
                        visualModelName);
        RuntimeModelConfiguration visualConfiguration = mock(RuntimeModelConfiguration.class);
        when(visualConfiguration.usesFake(Role.VISUAL, OWNER)).thenReturn(false);
        when(visualConfiguration.supportsVision(Role.VISUAL, OWNER)).thenReturn(true);
        when(visualConfiguration.providerFor(Role.VISUAL, OWNER)).thenReturn("qwen");
        when(visualConfiguration.modelNameFor(Role.VISUAL, OWNER)).thenReturn(visualModelName);
        when(visualConfiguration.modelFor(Role.VISUAL, OWNER))
                .thenReturn(recording(
                        visualDelegate,
                        rawCatalogResponses,
                        visualCallContext,
                        detailedRawCatalogResponses));
        SpringAiVisualRulebookPageCatalogModel catalog = model(visualConfiguration);

        List<GstonePageCatalogResult> pageResults = new ArrayList<>();
        Throwable catalogFailure = null;
        long catalogStarted = System.nanoTime();
        if (priorArtifact != null) {
            pageResults.addAll(reusablePageResults(priorArtifact, expectedPages));
        } else {
            ExecutorService visualExecutor = Executors.newFixedThreadPool(visualParallelism);
            try {
                List<Future<GstonePageCatalogResult>> futures = new ArrayList<>();
                for (int index = 0; index < pageImages.size(); index++) {
                    int pageNumber = index + 1;
                    URI pageImage = pageImages.get(index);
                    futures.add(visualExecutor.submit(() -> catalogGstonePage(
                            http,
                            pageImage,
                            pageNumber,
                            rulebookTitle,
                            catalog,
                            visualCallContext,
                            activeVisualRequests,
                            peakVisualRequests,
                            pageAttempts)));
                }
                for (Future<GstonePageCatalogResult> future : futures) {
                    pageResults.add(future.get(180, TimeUnit.SECONDS));
                }
            } catch (Throwable failure) {
                catalogFailure = failure;
            } finally {
                visualExecutor.shutdownNow();
            }
        }
        long catalogLatencyMs = Duration.ofNanos(System.nanoTime() - catalogStarted).toMillis();
        List<PageInput> pageInputs = pageResults.stream()
                .map(GstonePageCatalogResult::pageInput)
                .toList();

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("generatedAt", Instant.now().toString());
        artifact.put("sourceUrl", documentUrl);
        artifact.put("pageCount", pageImages.size());
        artifact.put("configuredVisualParallelism", priorArtifact == null
                ? visualParallelism
                : priorArtifact.path("configuredVisualParallelism").asInt(visualParallelism));
        artifact.put("peakVisualParallelism", priorArtifact == null
                ? peakVisualRequests.get()
                : priorArtifact.path("peakVisualParallelism").asInt());
        artifact.put("visualProvider", "qwen");
        artifact.put("visualModel", visualModelName);
        artifact.put("visualOcrModel", "qwen3.5-ocr");
        artifact.put("visualCatalogReusedFromArtifact", priorArtifact != null);
        artifact.put("visualModelCallsThisRun", rawCatalogResponses.size());
        artifact.put("visualModelCalls", priorArtifact == null
                ? rawCatalogResponses.size()
                : priorArtifact.path("visualModelCalls").asInt());
        artifact.put("visualCatalogLatencyMs", priorArtifact == null
                ? catalogLatencyMs
                : priorArtifact.path("visualCatalogLatencyMs").asLong());
        artifact.put("visualPageAttempts", priorArtifact == null
                ? pageAttempts.stream()
                        .sorted(java.util.Comparator.comparingInt(VisualCatalogAttempt::pageNumber)
                                .thenComparingInt(VisualCatalogAttempt::attempt))
                        .toList()
                : priorArtifact.path("visualPageAttempts"));
        artifact.put("rawCatalogResponses", priorArtifact == null
                ? detailedRawCatalogResponses.stream()
                        .sorted(java.util.Comparator.comparingInt(VisualCatalogRawResponse::pageNumber)
                                .thenComparingInt(VisualCatalogRawResponse::attempt)
                                .thenComparingInt(VisualCatalogRawResponse::responseIndex))
                        .map(response -> Map.of(
                                "pageNumber", response.pageNumber(),
                                "attempt", response.attempt(),
                                "responseIndex", response.responseIndex(),
                                "model", response.model(),
                                "kind", "qwen3.5-ocr".equals(response.model())
                                        ? "OCR_TRANSCRIPT"
                                        : "RULE_GROUPING",
                                "body", rawJsonOrText(response.body())))
                        .toList()
                : priorArtifact.path("rawCatalogResponses"));
        artifact.put("catalogStageComplete", catalogFailure == null
                && pageInputs.size() == expectedPages
                && pageInputs.stream().allMatch(PageInput::sourceRuleGroupInventoryComplete));
        if (catalogFailure != null) {
            artifact.put("catalogFailure", catalogFailure.toString());
        }
        writeArtifact(artifactPath, artifact);

        if (catalogFailure != null) {
            throw new AssertionError("bounded visual page catalog failed", catalogFailure);
        }
        assertThat(pageInputs).hasSize(expectedPages)
                .allSatisfy(page -> assertThat(page.sourceRuleGroupInventoryComplete()).isTrue());
        if (priorArtifact == null) {
            assertThat(peakVisualRequests).hasValue(visualParallelism);
        } else {
            assertThat(rawCatalogResponses).isEmpty();
        }

        String outlineProvider = environmentOrDefault(
                        "RULEPILOT_GSTONE_TEACHING_OUTLINE_PROVIDER", "deepseek")
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(outlineProvider).isIn("deepseek", "qwen");
        String environmentPrefix = outlineProvider.toUpperCase(java.util.Locale.ROOT);
        String outlineModelName = requiredEnvironment(environmentPrefix + "_MODEL");
        ChatModel outlineDelegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(
                        outlineProvider,
                        requiredEnvironment(environmentPrefix + "_API_KEY"),
                        requiredEnvironment(environmentPrefix + "_BASE_URL"),
                        outlineModelName);
        List<String> rawOutlineResponses = new java.util.ArrayList<>();
        RuntimeModelConfiguration outlineConfiguration = mock(RuntimeModelConfiguration.class);
        when(outlineConfiguration.usesFake(Role.TEACHING, OWNER)).thenReturn(false);
        when(outlineConfiguration.modelFor(Role.TEACHING, OWNER))
                .thenReturn(recording(outlineDelegate, rawOutlineResponses));
        when(outlineConfiguration.providerFor(Role.TEACHING, OWNER)).thenReturn(outlineProvider);
        when(outlineConfiguration.modelNameFor(Role.TEACHING, OWNER)).thenReturn(outlineModelName);
        when(outlineConfiguration.usesDeepSeekNonThinkingGeneration(Role.TEACHING, OWNER))
                .thenReturn("deepseek".equals(outlineProvider));
        VersionedAgentPrompts prompts = mock(VersionedAgentPrompts.class);
        when(prompts.structuredOutputRepair()).thenReturn("Return one complete compact JSON object matching the schema.");
        SpringAiTeachingOutlineModel outlineModel = new SpringAiTeachingOutlineModel(
                outlineConfiguration, prompts, new FakeTeachingOutlineModel());
        OutlineRequest outlineRequest = new OutlineRequest(
                List.copyOf(pageInputs),
                List.of(),
                "请先理解整局，再把复杂规则拆成第一次开桌能照做的清晰教学单元。",
                OWNER);
        long outlineStarted = System.nanoTime();
        OutlineDraft outline;
        try {
            outline = outlineModel.organize(outlineRequest);
        } catch (RuntimeException | Error failure) {
            artifact.put("outlineProvider", outlineProvider);
            artifact.put("outlineModel", outlineModelName);
            artifact.put("outlineModelCalls", rawOutlineResponses.size());
            artifact.put("outlineLatencyMs", Duration.ofNanos(System.nanoTime() - outlineStarted).toMillis());
            artifact.put("outlineStageComplete", false);
            artifact.put("outlineFailure", failure.toString());
            artifact.put("rawOutlineResponses", rawOutlineResponses.stream().map(this::rawJsonOrText).toList());
            writeArtifact(artifactPath, artifact);
            throw failure;
        } finally {
            outlineModel.close();
        }
        long outlineLatencyMs = Duration.ofNanos(System.nanoTime() - outlineStarted).toMillis();
        TeachingSourceCoverageContract.requireCompleteModelContract(outlineRequest, outline);
        var plan = new TeachingPlanFactory().create(
                java.util.UUID.nameUUIDFromBytes(documentUrl.getBytes(StandardCharsets.UTF_8)), OWNER, outline);

        int canonicalSlotCount = pageInputs.stream().mapToInt(page -> page.sourceRuleGroupIdentifiers().size()
                        + page.sourceDependencies().stream()
                                .mapToInt(dependency -> dependency.missingCoverageTags().size())
                                .sum())
                .sum();
        assertThat(outline.sourceCoverageSlots()).hasSize(canonicalSlotCount);
        assertThat(outline.sourceCoverageInventoryComplete()).isTrue();
        assertThat(outline.topics()).hasSizeBetween(3, 16).allSatisfy(topic -> {
            assertThat(topic.sourcePageNumbers()).hasSizeLessThanOrEqualTo(5);
            assertThat(topic.title()).isNotBlank();
            assertThat(topic.objective()).isNotBlank();
        });
        assertThat(outline.wholeGameUnderstanding().concepts()).isNotEmpty();
        assertThat(plan.wholeGameContext().evidenceBound()).isTrue();
        if (priorArtifact == null) {
            assertThat(rawCatalogResponses).hasSizeBetween(expectedPages * 2, expectedPages * 3);
        }
        assertThat(rawOutlineResponses).hasSizeBetween(1, 2);
        assertThat(rawOutlineResponses.getLast())
                .doesNotContain("sourceCoverageSlots", "sourceCoverageInventoryComplete", "sourcePageNumbers");
        assertThat(catalogLatencyMs + outlineLatencyMs).isLessThan(300_000L);

        artifact.put("outlineProvider", outlineProvider);
        artifact.put("outlineModel", outlineModelName);
        artifact.put("outlineModelCalls", rawOutlineResponses.size());
        artifact.put("outlineLatencyMs", outlineLatencyMs);
        artifact.put("outlineStageComplete", true);
        artifact.put("canonicalSlotCount", canonicalSlotCount);
        artifact.put("topicCount", outline.topics().size());
        artifact.put("wholeGameConceptCount", outline.wholeGameUnderstanding().concepts().size());
        artifact.put("sourceInventoryComplete", outline.sourceCoverageInventoryComplete());
        artifact.put("compactResponseOmittedCanonicalFields", true);
        artifact.put("rawOutlineResponses", rawOutlineResponses.stream().map(this::rawJsonOrText).toList());
        artifact.put("outline", json.valueToTree(outline));
        artifact.put("pageRuleGroupCounts", pageInputs.stream().map(page -> Map.of(
                "pageNumber", page.pageNumber(),
                "ruleGroupCount", page.sourceRuleGroupIdentifiers().size())).toList());
        writeArtifact(artifactPath, artifact);
    }

    @Test
    void catalogsOneRealGstonePageWithQuantityLineage() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_GSTONE_VISUAL_PAGE_CANARY")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        int pageNumber = Integer.parseInt(environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_PAGE_NUMBER", "16"));
        String documentUrl = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_URL",
                "https://www.gstonegames.com/game/doc-5689.html");
        String rulebookTitle = environmentOrDefault(
                "RULEPILOT_GSTONE_VISUAL_TEACHING_TITLE", "奋进号：深海");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        List<URI> pageImages = gstonePageImages(http, documentUrl);
        assertThat(pageNumber).isBetween(1, pageImages.size());

        String modelName = requiredEnvironment("QWEN_MODEL");
        List<String> rawResponses = new ArrayList<>();
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
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
        byte[] image = rgbJpeg(fetchBytes(http, pageImages.get(pageNumber - 1)));

        long started = System.nanoTime();
        PageSummary page;
        PageTranscript transcript = null;
        try {
            PageImageInput pageImage = new PageImageInput(pageNumber, "image/jpeg", image);
            transcript = catalog.transcribeTeachingPage(pageImage, OWNER);
            page = catalog.summarizeForTeaching(new CatalogRequest(
                            List.of(pageImage),
                            OWNER,
                            rulebookTitle,
                            List.of(transcript)))
                    .pages()
                    .getFirst();
        } catch (RuntimeException failure) {
            Map<String, Object> failedArtifact = new LinkedHashMap<>();
            failedArtifact.put("schemaVersion", 1);
            failedArtifact.put("generatedAt", Instant.now().toString());
            failedArtifact.put("sourceUrl", documentUrl);
            failedArtifact.put("pageNumber", pageNumber);
            failedArtifact.put("provider", "qwen");
            failedArtifact.put("model", modelName);
            failedArtifact.put("modelCalls", rawResponses.size());
            failedArtifact.put("ocrTranscript", transcript == null ? null : transcript.text());
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
        assertThat(rawResponses).hasSizeBetween(2, 3);

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("generatedAt", Instant.now().toString());
        artifact.put("sourceUrl", documentUrl);
        artifact.put("pageNumber", pageNumber);
        artifact.put("provider", "qwen");
        artifact.put("model", modelName);
        artifact.put("modelCalls", rawResponses.size());
        artifact.put("latencyMs", latencyMs);
        artifact.put("ocrModel", "qwen3.5-ocr");
        artifact.put("ocrTranscript", transcript.text());
        artifact.put("rawResponses", rawResponses.stream().map(this::rawJsonOrText).toList());
        artifact.put("pageSummary", json.valueToTree(page));
        writeArtifact(
                root.resolve(".local/agent-evaluation/gstone-visual-page-" + pageNumber + "-canary.json"),
                artifact);
    }

    private SpringAiVisualRulebookPageCatalogModel model(RuntimeModelConfiguration configuration) throws Exception {
        return new SpringAiVisualRulebookPageCatalogModel(
                configuration,
                new FakeVisualRulebookPageCatalogModel(),
                new ClassPathResource("prompts/visual-page-catalog-v2-icon-inventory-system.txt"),
                new ClassPathResource("prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt"),
                new ClassPathResource("prompts/visual-page-progressive-teaching-start-v4-source-contract-system.txt"),
                new ClassPathResource("prompts/visual-icon-localization-v2-system.txt"),
                new ClassPathResource("prompts/visual-icon-crop-review-v4-system.txt"),
                new ClassPathResource("prompts/visual-identifier-cell-v1-system.txt"),
                new ClassPathResource("prompts/visual-identifier-reference-match-v1-system.txt"),
                "qwen3.5-ocr",
                4_800);
    }

    private GstonePageCatalogResult catalogGstonePage(
            HttpClient http,
            URI imageUri,
            int pageNumber,
            String rulebookTitle,
            SpringAiVisualRulebookPageCatalogModel catalog,
            ThreadLocal<VisualCallContext> callContext,
            AtomicInteger activeRequests,
            AtomicInteger peakRequests,
            List<VisualCatalogAttempt> pageAttempts)
            throws Exception {
        byte[] image = rgbJpeg(fetchBytes(http, imageUri));
        PageSummary lastSummary = null;
        PageTranscript transcript = null;
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long started = System.nanoTime();
            int active = activeRequests.incrementAndGet();
            peakRequests.accumulateAndGet(active, Math::max);
            callContext.set(new VisualCallContext(pageNumber, attempt));
            try {
                PageImageInput pageImage = new PageImageInput(pageNumber, "image/jpeg", image);
                if (transcript == null) transcript = catalog.transcribeTeachingPage(pageImage, OWNER);
                lastSummary = catalog.summarizeForTeaching(new CatalogRequest(
                                List.of(pageImage),
                                OWNER,
                                rulebookTitle,
                                List.of(transcript)))
                        .pages()
                        .getFirst();
                pageAttempts.add(new VisualCatalogAttempt(
                        pageNumber,
                        attempt,
                        lastSummary.ruleGroupInventoryComplete(),
                        lastSummary.ruleGroupIdentifiers().size(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        ""));
                if (lastSummary.ruleGroupInventoryComplete()) break;
            } catch (Throwable failure) {
                lastFailure = failure;
                pageAttempts.add(new VisualCatalogAttempt(
                        pageNumber,
                        attempt,
                        false,
                        0,
                        Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        failure.toString()));
            } finally {
                callContext.remove();
                activeRequests.decrementAndGet();
            }
        }
        if (lastSummary == null) {
            throw new IllegalStateException("visual catalog did not return page " + pageNumber, lastFailure);
        }
        return new GstonePageCatalogResult(pageInput(lastSummary));
    }

    private List<GstonePageCatalogResult> reusablePageResults(JsonNode artifact, int expectedPages) {
        Map<Integer, PageSummary> completePages = new java.util.TreeMap<>();
        JsonNode responses = artifact.path("rawCatalogResponses");
        if (!artifact.path("catalogStageComplete").asBoolean() || !responses.isArray()) {
            throw new IllegalArgumentException("reusable visual catalog artifact is incomplete");
        }
        for (JsonNode response : responses) {
            try {
                SpringAiVisualRulebookPageCatalogModel.parseTeachingCatalogV6(
                                response.path("body").toString())
                        .pages()
                        .stream()
                        .filter(PageSummary::ruleGroupInventoryComplete)
                        .forEach(page -> completePages.put(page.pageNumber(), page));
            } catch (IllegalArgumentException invalidProviderAttempt) {
                // The artifact intentionally retains first-pass provider failures before its successful local repair.
            }
        }
        if (completePages.size() != expectedPages
                || !completePages.keySet().equals(java.util.stream.IntStream.rangeClosed(1, expectedPages)
                        .boxed()
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)))) {
            throw new IllegalArgumentException("reusable visual catalog artifact omitted a complete page");
        }
        return completePages.values().stream()
                .map(this::pageInput)
                .map(GstonePageCatalogResult::new)
                .toList();
    }

    private PageInput pageInput(PageSummary summary) {
        String factualSummary = VisualQuantityObservation.appendEvidence(
                summary.factualSummary(), summary.quantityObservations());
        return new PageInput(
                summary.pageNumber(),
                "[Visual page catalog; verify against page image]\nPrinted terms: "
                        + summary.printedTerms()
                        + "\nVisible facts:\n"
                        + factualSummary
                        + "\nKeywords: "
                        + String.join("; ", summary.keywords()),
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete());
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

    private ChatModel recording(
            ChatModel delegate,
            List<String> rawResponses,
            ThreadLocal<VisualCallContext> callContext,
            List<VisualCatalogRawResponse> detailedResponses) {
        AtomicInteger responseIndex = new AtomicInteger();
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                ChatResponse response = delegate.call(prompt);
                String content = response == null || response.getResult() == null
                                || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                String recorded = content == null ? "" : content;
                rawResponses.add(recorded);
                VisualCallContext context = callContext.get();
                String model = prompt.getOptions() instanceof OpenAiChatOptions options
                        ? options.getModel()
                        : "";
                detailedResponses.add(new VisualCatalogRawResponse(
                        context == null ? 0 : context.pageNumber(),
                        context == null ? 0 : context.attempt(),
                        responseIndex.incrementAndGet(),
                        model,
                        recorded));
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

    private byte[] render(Path pdf, int pageNumber) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, 144, ImageType.RGB);
            assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
            return output.toByteArray();
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

    private record VisualCallContext(int pageNumber, int attempt) {}

    private record VisualCatalogRawResponse(
            int pageNumber, int attempt, int responseIndex, String model, String body) {}

    private record VisualCatalogAttempt(
            int pageNumber,
            int attempt,
            boolean inventoryComplete,
            int ruleGroupCount,
            long latencyMs,
            String failure) {}

    private record GstonePageCatalogResult(PageInput pageInput) {}
}
