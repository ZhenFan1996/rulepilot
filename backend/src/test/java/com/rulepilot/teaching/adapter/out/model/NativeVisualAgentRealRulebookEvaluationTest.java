package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolScopes;
import com.rulepilot.assistant.NativeVisualEvidence;
import com.rulepilot.assistant.adapter.out.model.SpringAiNativeToolModel;
import com.rulepilot.assistant.application.BoundedNativeToolAgent;
import com.rulepilot.assistant.application.CropRulePageImageNativeTool;
import com.rulepilot.assistant.application.NativeAgentToolRegistry;
import com.rulepilot.assistant.application.ReadRulePageImageNativeTool;
import com.rulepilot.assistant.application.ReadVisualPageFactsNativeTool;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.VisualRegionLocator.Diagnostic;
import com.rulepilot.teaching.VisualRegionLocator.LocateGuideResult;
import com.rulepilot.teaching.VisualRegionLocator.LocatedRegion;
import com.rulepilot.teaching.application.VisualRegionCandidateSelector.Candidate;
import io.micrometer.observation.ObservationRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

@Tag("real-visual-agent-evaluation")
class NativeVisualAgentRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void inspectsThreeVisualFamiliesWithCitedHandlesAndPreservesAbstention() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_VISUAL_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/visual-agent-cases.json").toFile());
        ProviderConfiguration provider = provider();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode node : configuration.path("cases")) {
            Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("pdf").asText());
            assumeTrue(Files.isRegularFile(pdf), "ignored real visual rulebook is required");
            results.add(runCase(node, pdf, provider));
        }

        assertThat(results).hasSize(3);
        assertThat(results.subList(0, 2)).allSatisfy(result -> {
            assertThat(result).containsEntry("outcome", "FOUND")
                    .containsEntry("targetIntersected", true)
                    .containsEntry("compactCrop", true)
                    .containsEntry("mechanicalRuleAuthority", false);
            assertThat((Integer) result.get("toolCalls")).isGreaterThanOrEqualTo(2);
        });
        assertThat(results.getLast()).containsEntry("outcome", "EXPLICIT_NO_REGION")
                .containsEntry("publishedCropCount", 0);

        Map<String, Object> controls = controls(provider);
        assertThat(controls).containsEntry("inventedPageRejected", true)
                .containsEntry("crossScopeMediaCount", 0)
                .containsEntry("textOnlyFallbackPreserved", true);

        Files.writeString(
                root.resolve(".local/agent-evaluation/visual-agent-real-rulebooks.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "schemaVersion", 1,
                        "generatedAt", Instant.now().toString(),
                        "results", results,
                        "controls", controls)) + "\n",
                StandardCharsets.UTF_8);
    }

    private Map<String, Object> runCase(
            JsonNode node, Path pdf, ProviderConfiguration provider) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(node.path("caseId").asText().getBytes(StandardCharsets.UTF_8));
        UUID evidenceId = UUID.nameUUIDFromBytes((node.path("caseId").asText() + ":evidence")
                .getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        int pageNumber = node.path("pageNumber").asInt();
        PdfVisualEvidence evidence = new PdfVisualEvidence(pdf, versionId, evidenceId, pageNumber);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        NativeToolAgent loop = loop(provider, evidence, audited);
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        NativeToolScopes scopes = (owner, document, run) -> owner.equals(scope.ownerUsername())
                        && document.equals(scope.documentVersionId())
                        && run.equals(scope.runId())
                ? Optional.of(scope)
                : Optional.empty();
        UnavailableFallback fallback = new UnavailableFallback();
        AgenticVisualRegionLocator locator = new AgenticVisualRegionLocator(loop, scopes, fallback, mapper);
        JsonNode target = node.path("target");
        Rectangle targetRectangle = new Rectangle(
                target.path("x").asInt(),
                target.path("y").asInt(),
                target.path("width").asInt(),
                target.path("height").asInt());
        VisualRegionLocator.VisualLocationRequest request = new VisualRegionLocator.VisualLocationRequest(
                "Opaque visual evaluation chapter",
                List.of(new VisualRegionLocator.Claim(
                        evidenceId, node.path("claim").asText(), List.of(pageNumber), 1)),
                List.of(new Candidate(pageNumber, targetRectangle, "page visual context")),
                List.of(new VisualRegionLocator.PageImage(
                        pageNumber, evidence.page.mediaType(), evidence.page.content())),
                scope.ownerUsername(),
                versionId,
                runId);
        long started = System.nanoTime();

        LocateGuideResult result = locator.locateGuideWithResult(request);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        boolean targetIntersected = result.regions().stream()
                .allMatch(region -> intersects(region, targetRectangle));
        boolean compact = result.regions().stream()
                .allMatch(region -> (long) region.width() * region.height() < 600_000L);
        boolean authority = loopResultAuthority(audited.observations);
        return Map.ofEntries(
                Map.entry("caseId", node.path("caseId").asText()),
                Map.entry("provider", provider.provider()),
                Map.entry("outcome", result.diagnostic().name()),
                Map.entry("publishedCropCount", result.regions().size()),
                Map.entry("toolCalls", audited.toolCalls),
                Map.entry("modelCalls", audited.modelCalls),
                Map.entry("toolCodes", audited.observations.stream().map(ToolObservation::code).toList()),
                Map.entry("targetIntersected", targetIntersected),
                Map.entry("compactCrop", compact),
                Map.entry("mechanicalRuleAuthority", authority),
                Map.entry("fallbackCalls", fallback.calls),
                Map.entry("latencyMs", latencyMs),
                Map.entry("withinLatencyBudget", latencyMs < 90_000));
    }

    private Map<String, Object> controls(ProviderConfiguration provider) throws IOException {
        UUID versionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        PdfVisualEvidence evidence = new PdfVisualEvidence(
                root.resolve(".local/public-corpus/pdfs/calico.pdf"), versionId, evidenceId, 4);
        ReadRulePageImageNativeTool tool = new ReadRulePageImageNativeTool(evidence, mapper);
        var rejected = tool.execute(
                "{\"evidenceId\":\"" + evidenceId + "\",\"pageNumber\":5}",
                new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(30)));

        UnavailableFallback fallback = new UnavailableFallback();
        NativeToolAgent textOnly = new NativeToolAgent() {
            @Override public RunResult run(RunRequest request) { throw new AssertionError("text-only model was called"); }
            @Override public boolean supports(Role role, String ownerUsername) { return false; }
        };
        NativeToolScopes scopes = (owner, document, run) -> Optional.of(
                new ToolScope(owner, document, run, Instant.now().plusSeconds(30)));
        AgenticVisualRegionLocator locator = new AgenticVisualRegionLocator(textOnly, scopes, fallback, mapper);
        locator.locateGuideWithResult(new VisualRegionLocator.VisualLocationRequest(
                "Text-only fallback control",
                List.of(new VisualRegionLocator.Claim(evidenceId, "核对一个可见组件。", List.of(4), 1)),
                List.of(new Candidate(4, new Rectangle(0, 0, 1000, 1000), "page visual context")),
                List.of(new VisualRegionLocator.PageImage(4, evidence.page.mediaType(), evidence.page.content())),
                "agent-evaluation",
                versionId,
                runId));
        return Map.of(
                "inventedPageRejected", rejected.evidenceCount() == 0,
                "crossScopeMediaCount", rejected.media().size(),
                "textOnlyFallbackPreserved", fallback.calls == 1,
                "providerConfigured", provider.model() != null);
    }

    private NativeToolAgent loop(
            ProviderConfiguration provider,
            NativeVisualEvidence evidence,
            DirectAuditedInvocations audited) {
        NativeAgentToolRegistry registry = new NativeAgentToolRegistry(
                List.of(
                        new ReadRulePageImageNativeTool(evidence, mapper),
                        new CropRulePageImageNativeTool(evidence, mapper),
                        new ReadVisualPageFactsNativeTool(evidence, mapper)),
                mapper,
                ignored -> true);
        return new BoundedNativeToolAgent(
                springModel(provider), registry, mock(AgentExecutionControl.class), audited, mapper);
    }

    private NativeToolModel springModel(ProviderConfiguration provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(60))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(chatModel);
        when(configuration.providerFor(any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn(provider.provider());
        when(configuration.supportsVision(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(true);
        when(configuration.usesFake(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(false);
        return new SpringAiNativeToolModel(configuration);
    }

    private ProviderConfiguration provider() {
        return new ProviderConfiguration(
                "qwen",
                requiredEnvironment("QWEN_API_KEY"),
                requiredEnvironment("QWEN_BASE_URL"),
                requiredEnvironment("QWEN_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private boolean intersects(LocatedRegion region, Rectangle target) {
        int left = Math.max(region.x(), target.x());
        int top = Math.max(region.y(), target.y());
        int right = Math.min(region.x() + region.width(), target.x() + target.width());
        int bottom = Math.min(region.y() + region.height(), target.y() + target.height());
        return right > left && bottom > top;
    }

    private boolean loopResultAuthority(List<ToolObservation> observations) {
        return observations.stream()
                .filter(observation -> observation.data().containsKey("mechanicalRuleAuthority"))
                .anyMatch(observation -> Boolean.TRUE.equals(observation.data().get("mechanicalRuleAuthority")));
    }

    private record ProviderConfiguration(String provider, String apiKey, String baseUrl, String model) {}

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private int modelCalls;
        private int toolCalls;
        private final List<ToolObservation> observations = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelCalls++;
            if (type == ActivityType.TOOL) toolCalls++;
            T result = invocation.get();
            if (result instanceof NativeAgentToolRegistry.ToolExecution execution) {
                observations.add(execution.observation());
            }
            return result;
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
    }

    private static final class PdfVisualEvidence implements NativeVisualEvidence {
        private final UUID versionId;
        private final UUID evidenceId;
        private final VisualPage page;

        private PdfVisualEvidence(Path pdf, UUID versionId, UUID evidenceId, int pageNumber) throws IOException {
            this.versionId = versionId;
            this.evidenceId = evidenceId;
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                BufferedImage image = new PDFRenderer(document).renderImageWithDPI(
                        pageNumber - 1, 110, ImageType.RGB);
                this.page = new VisualPage(
                        evidenceId,
                        pageNumber,
                        "image/png",
                        encode(image, "png"),
                        image.getWidth(),
                        image.getHeight());
            }
        }

        @Override
        public Optional<VisualPage> readPage(UUID documentVersionId, UUID evidenceId, int pageNumber) {
            return matches(documentVersionId, evidenceId, pageNumber) ? Optional.of(page) : Optional.empty();
        }

        @Override
        public Optional<VisualCrop> cropPage(
                UUID documentVersionId,
                UUID evidenceId,
                int pageNumber,
                int x,
                int y,
                int width,
                int height) {
            if (!matches(documentVersionId, evidenceId, pageNumber)
                    || x < 0 || y < 0 || width < 12 || height < 12
                    || x + width > 1_000 || y + height > 1_000) {
                return Optional.empty();
            }
            try {
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(page.content()));
                int left = x * image.getWidth() / 1_000;
                int top = y * image.getHeight() / 1_000;
                int right = Math.max(left + 1, Math.min(image.getWidth(), (x + width) * image.getWidth() / 1_000));
                int bottom = Math.max(top + 1, Math.min(image.getHeight(), (y + height) * image.getHeight() / 1_000));
                BufferedImage crop = image.getSubimage(left, top, right - left, bottom - top);
                return Optional.of(new VisualCrop(
                        evidenceId,
                        pageNumber,
                        "image/jpeg",
                        encode(crop, "jpeg"),
                        x,
                        y,
                        width,
                        height,
                        crop.getWidth(),
                        crop.getHeight()));
            } catch (IOException failure) {
                throw new IllegalStateException("could not crop real evaluation page", failure);
            }
        }

        @Override
        public List<VisualPageFact> readPageFacts(UUID documentVersionId, UUID evidenceId, int pageNumber) {
            return List.of();
        }

        private boolean matches(UUID documentVersionId, UUID evidenceId, int pageNumber) {
            return versionId.equals(documentVersionId)
                    && this.evidenceId.equals(evidenceId)
                    && page.pageNumber() == pageNumber;
        }

        private static byte[] encode(BufferedImage image, String format) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) throw new IOException("image encoder unavailable");
            return output.toByteArray();
        }
    }

    private static final class UnavailableFallback implements VisualRegionLocator {
        private int calls;

        @Override
        public Optional<LocatedRegion> locate(VisualLocationRequest request) {
            calls++;
            return Optional.empty();
        }

        @Override
        public LocateGuideResult locateGuideWithResult(VisualLocationRequest request) {
            calls++;
            return LocateGuideResult.unavailable(Diagnostic.MODEL_UNAVAILABLE);
        }
    }
}
