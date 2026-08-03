package com.rulepilot.assistant.evaluation;

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
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeToolAgent.RunStatus;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiNativeToolModel;
import com.rulepilot.assistant.application.BoundedNativeToolAgent;
import com.rulepilot.assistant.application.NativeAgentToolRegistry;
import com.rulepilot.assistant.application.ReadRulePagesNativeTool;
import com.rulepilot.assistant.application.SearchRuleEvidenceNativeTool;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

@Tag("real-agent-evaluation")
class NativeToolRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void provesTheBoundedNativeLoopAcrossTwoIgnoredRealRulebooksAndTwoConcreteModels() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        List<CaseConfiguration> cases = List.of(
                caseFor(root, manifest, inventory, "TEXT_LAYER", provider("deepseek")),
                caseFor(root, manifest, inventory, "LONG_EXCEPTION_HEAVY", provider("qwen")));

        List<Map<String, Object>> results = new ArrayList<>();
        for (CaseConfiguration case_ : cases) results.add(runCase(case_));

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result).containsEntry("nativeLoopStatus", "COMPLETED")
                    .containsEntry("noToolCalls", 0)
                    .containsEntry("invalidArgumentRejected", true)
                    .containsEntry("timeoutFallback", true)
                    .containsEntry("providerFallback", true);
            assertThat((Integer) result.get("toolCalls")).isGreaterThanOrEqualTo(2);
        });
        Path output = root.resolve(".local/agent-evaluation/native-tool-loop-real-rulebooks.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
    }

    private Map<String, Object> runCase(CaseConfiguration case_) throws IOException {
        UUID documentVersionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        ToolScope scope = new ToolScope("agent-evaluation", documentVersionId, runId, Instant.now().plusSeconds(120));
        PdfAssistantReadTools pdfTools = new PdfAssistantReadTools(case_.pdf(), documentVersionId);
        var nativeTools = List.of(
                new SearchRuleEvidenceNativeTool(pdfTools, mapper),
                new ReadRulePagesNativeTool(pdfTools, mapper));
        NativeAgentToolRegistry registry = new NativeAgentToolRegistry(
                nativeTools,
                mapper,
                candidate -> candidate.ownerUsername().equals(scope.ownerUsername())
                        && candidate.documentVersionId().equals(scope.documentVersionId()));
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        NativeToolModel model = springModel(case_.provider());
        BoundedNativeToolAgent agent = new BoundedNativeToolAgent(
                model, registry, mock(AgentExecutionControl.class), audited, mapper);

        RunResult nativeResult = agent.run(new RunRequest(
                Role.ANSWER,
                scope,
                """
                You are testing a read-only rulebook tool protocol for a board-game player. You have no rulebook
                knowledge outside tool observations. First search for evidence that helps a player set up and begin
                play. Then use read_rule_pages on one relevant page from that observation. Only after both relevant
                observations, return a brief cited readiness summary. Never invent a rule.
                """,
                "Help a first-time player know where to verify setup and the first playable step.",
                "Insufficient verified evidence.",
                5,
                512));
        assertThat(nativeResult.status())
                .withFailMessage(
                        "native loop failed safely: reason=%s iterations=%s toolCalls=%s observationCodes=%s",
                        nativeResult.reason(),
                        nativeResult.iterations(),
                        nativeResult.toolCalls(),
                        nativeResult.observations().stream()
                                .map(observation -> observation.observation().code())
                                .toList()
                                + ", failureClass=" + audited.lastFailureClass)
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(nativeResult.observations()).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(observation -> assertThat(observation.observation().status().name()).isNotEqualTo("ERROR"));
        assertThat(nativeResult.observations()).extracting(observation -> observation.toolName())
                .contains("search_rule_evidence", "read_rule_pages");

        RunResult noTool = agent.run(new RunRequest(
                Role.ANSWER,
                new ToolScope(scope.ownerUsername(), scope.documentVersionId(), UUID.randomUUID(), Instant.now().plusSeconds(60)),
                "This is a protocol control. Use no tools when the request needs no rule evidence.",
                "Reply exactly READY without calling a tool.",
                "CONTROL_FAILED",
                2,
                64));
        assertThat(noTool.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(noTool.toolCalls()).isZero();

        int searchesBeforeInvalid = pdfTools.searches;
        var invalid = registry.execute(Role.ANSWER, "search_rule_evidence", "{\"limit\":99}", scope);
        assertThat(invalid.observation().code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(pdfTools.searches).isEqualTo(searchesBeforeInvalid);

        BoundedNativeToolAgent timeoutAgent = new BoundedNativeToolAgent(
                ignored -> { throw new AssertionError("expired loop called model"); },
                registry,
                mock(AgentExecutionControl.class),
                audited,
                mapper);
        RunResult timeout = timeoutAgent.run(controlRequest(new ToolScope(
                scope.ownerUsername(), scope.documentVersionId(), UUID.randomUUID(), Instant.now().minusSeconds(1))));
        assertThat(timeout.reason()).isEqualTo("TIMEOUT");

        BoundedNativeToolAgent failedProviderAgent = new BoundedNativeToolAgent(
                ignored -> { throw new IllegalStateException("synthetic provider failure"); },
                registry,
                mock(AgentExecutionControl.class),
                audited,
                mapper);
        RunResult providerFallback = failedProviderAgent.run(controlRequest(new ToolScope(
                scope.ownerUsername(), scope.documentVersionId(), UUID.randomUUID(), Instant.now().plusSeconds(10))));
        assertThat(providerFallback.status()).isEqualTo(RunStatus.FALLBACK);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("model", case_.provider().model());
        result.put("nativeLoopStatus", nativeResult.status().name());
        result.put("iterations", nativeResult.iterations());
        result.put("toolCalls", nativeResult.toolCalls());
        result.put("observationCodes", nativeResult.observations().stream()
                .map(observation -> observation.observation().code()).toList());
        result.put("evidenceCounts", nativeResult.observations().stream()
                .map(observation -> observation.observation().evidenceCount()).toList());
        result.put("noToolCalls", noTool.toolCalls());
        result.put("invalidArgumentRejected", invalid.observation().evidenceCount() == 0);
        result.put("timeoutFallback", timeout.status() == RunStatus.FALLBACK);
        result.put("providerFallback", providerFallback.status() == RunStatus.FALLBACK);
        result.put("auditedModelCalls", audited.modelCalls);
        result.put("auditedToolCalls", audited.toolCalls);
        return Map.copyOf(result);
    }

    private RunRequest controlRequest(ToolScope scope) {
        return new RunRequest(
                Role.ANSWER,
                scope,
                "Use tools only when needed.",
                "Protocol control.",
                "SAFE_FALLBACK",
                2,
                64);
    }

    private NativeToolModel springModel(ProviderConfiguration provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(45))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(chatModel);
        when(configuration.providerFor(any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn(provider.provider());
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn("deepseek".equals(provider.provider()));
        return new SpringAiNativeToolModel(configuration);
    }

    private ProviderConfiguration provider(String provider) {
        String prefix = provider.toUpperCase(Locale.ROOT);
        String apiKey = requiredEnvironment(prefix + "_API_KEY");
        String baseUrl = requiredEnvironment(prefix + "_BASE_URL");
        String model = requiredEnvironment(prefix + "_MODEL");
        return new ProviderConfiguration(provider, apiKey, baseUrl, model);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private CaseConfiguration caseFor(
            Path root,
            JsonNode manifest,
            JsonNode inventory,
            String family,
            ProviderConfiguration provider) {
        JsonNode caseNode = java.util.stream.StreamSupport.stream(manifest.path("cases").spliterator(), false)
                .filter(candidate -> family.equals(candidate.path("family").asText()))
                .findFirst()
                .orElseThrow();
        String digest = caseNode.path("sourceSha256").asText();
        JsonNode source = java.util.stream.StreamSupport.stream(
                        inventory.path("qualifiedRulebooks").spliterator(), false)
                .filter(candidate -> digest.equals(candidate.path("sha256").asText()))
                .findFirst()
                .orElseThrow();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(source.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required");
        return new CaseConfiguration(caseNode.path("caseId").asText(), pdf, provider);
    }

    private record CaseConfiguration(String caseId, Path pdf, ProviderConfiguration provider) {}

    private record ProviderConfiguration(String provider, String apiKey, String baseUrl, String model) {}

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private int modelCalls;
        private int toolCalls;
        private String lastFailureClass = "none";

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
            try {
                return invocation.get();
            } catch (RuntimeException exception) {
                lastFailureClass = exception.getClass().getSimpleName();
                throw exception;
            }
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
    }

    private static final class PdfAssistantReadTools implements AssistantReadTools {
        private final UUID documentVersionId;
        private final List<String> pages;
        private int searches;

        private PdfAssistantReadTools(Path pdf, UUID documentVersionId) throws IOException {
            this.documentVersionId = documentVersionId;
            this.pages = extractPages(pdf.toFile());
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!documentVersionId.equals(request.documentVersionId())) {
                throw new IllegalArgumentException("document scope mismatch");
            }
            searches++;
            Set<String> terms = java.util.Arrays.stream(request.query().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                    .filter(term -> term.length() > 2)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return java.util.stream.IntStream.range(0, pages.size())
                    .mapToObj(index -> new ScoredPage(index + 1, pages.get(index), score(pages.get(index), terms)))
                    .filter(page -> !page.text().isBlank())
                    .sorted(Comparator.comparingInt(ScoredPage::score).reversed().thenComparingInt(ScoredPage::page))
                    .limit(request.limit())
                    .map(this::evidence)
                    .toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            if (!this.documentVersionId.equals(documentVersionId) || includePageImages) {
                throw new IllegalArgumentException("page scope mismatch");
            }
            return pageNumbers.stream()
                    .sorted()
                    .filter(page -> page <= pages.size())
                    .map(page -> evidence(new ScoredPage(page, pages.get(page - 1), 0)))
                    .toList();
        }

        private RuleEvidence evidence(ScoredPage page) {
            String excerpt = page.text().replaceAll("\\s+", " ").strip();
            if (excerpt.length() > 1600) excerpt = excerpt.substring(0, 1600);
            return new RuleEvidence(
                    UUID.nameUUIDFromBytes((documentVersionId + ":" + page.page()).getBytes(StandardCharsets.UTF_8)),
                    documentVersionId,
                    "PAGE",
                    "Rulebook page " + page.page(),
                    excerpt,
                    page.page(),
                    page.page());
        }

        private int score(String page, Set<String> terms) {
            String lower = page.toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(lower::contains).count();
        }

        private static List<String> extractPages(File pdf) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                List<String> pages = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(stripper.getText(document));
                }
                return List.copyOf(pages);
            }
        }

        private record ScoredPage(int page, String text, int score) {}
    }
}
